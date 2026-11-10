package app.shakefinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures engine speed from the vibration spectrum and follows it over time.
 *
 * Step 1, the series. An engine's peaks sit on one harmonic series. The spacing
 * of that series is the largest frequency that still explains nearly all of the
 * peak energy. Peaks that belong to the tyres or the propshaft are left out
 * first when the road speed is known.
 *
 * Step 2, the octave. The spacing may be the ½, 1st, 2nd or 4th engine order.
 * Vibration alone can't prove which, so each reading is scored: a four-cylinder
 * normally shakes hardest at its firing frequency (2nd order), most of the
 * energy should sit on firing harmonics, and the rpm has to be one an engine
 * can run at. The best score wins. A reading close to the previous one gets a
 * small bonus so the display doesn't flip between two near-equal answers.
 *
 * Step 3, the lock. If the driver says "the tach shows about X", that reading
 * is strongly preferred from then on, and the lock follows the engine as the
 * rpm moves.
 *
 * The same tracker reads engine sound. Sound peaks are scored a little
 * differently: a microphone hears the firing harmonics (2nd, 4th, 6th…) and any
 * of them can be the loudest, so the spacing itself is taken as the firing
 * frequency unless the evidence says otherwise. A second source can also back
 * up a reading: when sound and vibration agree, that reading wins.
 *
 * Plain Java, no Android imports.
 */
public final class RpmTracker {

    public enum Kind { VIBRATION, SOUND }

    private static final double[] SPACING_ORDERS = {0.5, 1, 2, 4};
    private static final double MIN_SNR = 6;
    private static final double SUPPORT_BOOST = 4;     // a second source agreeing counts this much
    private static final double KEEP_FRACTION = 0.06;  // ignore peaks below 6% of the largest
    private static final double MIN_COVERAGE = 0.6;    // the series must explain most of the peak energy
    private static final double NEAR_BEST = 0.85;
    private static final double STICKY = 1.5;          // bonus for agreeing with the last reading
    private static final double LOCK_BOOST = 8;
    private static final double LOCK_LIFE_S = 300;
    private static final double AMBIGUOUS = 0.45;      // runner-up this close to the winner
    private static final double OFFER = 0.10;          // alternatives weaker than this aren't worth showing

    /** One reading of engine speed. */
    public static final class Estimate {
        public final double rpm;
        public final double spacingHz;     // measured harmonic spacing
        public final double coverage;      // share of peak energy on the engine series, 0..1
        public final int matched;          // peaks on the series
        public final double confidence;    // 0..1
        public final boolean ambiguous;    // another octave fits nearly as well
        public final boolean locked;       // following a reading the driver confirmed
        public final double domHz;         // strongest engine peak
        public final double domOrder;      // its engine order under this reading
        public final List<Double> alternatives; // the other readings an engine could run at

        Estimate(double rpm, double spacingHz, double coverage, int matched, double confidence,
                 boolean ambiguous, boolean locked, double domHz, double domOrder, List<Double> alternatives) {
            this.rpm = rpm;
            this.spacingHz = spacingHz;
            this.coverage = coverage;
            this.matched = matched;
            this.confidence = confidence;
            this.ambiguous = ambiguous;
            this.locked = locked;
            this.domHz = domHz;
            this.domOrder = domOrder;
            this.alternatives = alternatives;
        }
    }

    /** The harmonic series found in one spectrum. */
    static final class Fit {
        double spacing, coverage, domHz, domAmp, domSnr, matchedAmp;
        int matched;
        final List<Analysis.Peak> onSeries = new ArrayList<>();
        final List<Integer> harmonic = new ArrayList<>();
    }

    private final Kind kind;
    private final double minAmp;
    private final int minMatched;

    private double lastRpm = Double.NaN, lastT = -1e9;
    private double lockRpm = Double.NaN, lockOrder = Double.NaN, lockT = -1e9;
    private double lastDomHz = Double.NaN;

    /** A tracker for accelerometer peaks (amplitudes in m/s²). */
    public RpmTracker() {
        this(Kind.VIBRATION);
    }

    public RpmTracker(Kind kind) {
        this.kind = kind;
        // Vibration: about 1.2 mg. Sound peaks arrive as "times louder than the
        // background", and one lone tone could be a fan, so two are needed.
        this.minAmp = kind == Kind.VIBRATION ? 0.012 : 8;
        this.minMatched = kind == Kind.VIBRATION ? 1 : 2;
    }

    public void reset() {
        lastRpm = Double.NaN;
        lastT = -1e9;
        lastDomHz = Double.NaN;
        unlock();
    }

    /** The driver says the tachometer shows about this. */
    public void lock(double rpm, double tSeconds) {
        if (rpm <= 0) return;
        lockRpm = rpm;
        lockT = tSeconds;
        lockOrder = Double.isNaN(lastDomHz) ? Double.NaN : Math.round(lastDomHz / (rpm / 60.0) * 2) / 2.0;
        lastRpm = rpm;
        lastT = tSeconds;
    }

    public void unlock() {
        lockRpm = Double.NaN;
        lockOrder = Double.NaN;
        lockT = -1e9;
    }

    public boolean isLocked() {
        return !Double.isNaN(lockRpm);
    }

    /**
     * @param peaks      spectrum peaks, any order
     * @param df         spectrum resolution in Hz
     * @param excludeHz  frequencies owned by tyres and propshaft, or null
     * @param excludeTol relative width around each excluded frequency, e.g. 0.035
     * @param tSeconds   a clock in seconds, for continuity
     * @param support    readings from another source as {rpm, weight 0..1} pairs, or null
     * @return the reading, or null when no engine series stands out
     */
    public Estimate update(List<Analysis.Peak> peaks, double df, double[] excludeHz, double excludeTol,
                           double tSeconds, double[][] support) {
        Fit fit = fit(peaks, df, excludeHz, excludeTol, minAmp);
        if (fit == null && excludeHz != null && excludeHz.length > 0) fit = fit(peaks, df, null, 0, minAmp);
        if (fit == null || fit.matched < minMatched) return null;
        lastDomHz = fit.domHz;
        if (isLocked() && tSeconds - lockT > LOCK_LIFE_S) unlock();

        int nq = SPACING_ORDERS.length;
        double[] rpm = new double[nq], score = new double[nq], order = new double[nq], plain = new double[nq];
        double maxPlain = 0;
        boolean[] boosted = new boolean[nq];
        int best = -1;
        for (int i = 0; i < nq; i++) {
            double q = SPACING_ORDERS[i];
            rpm[i] = 60.0 * fit.spacing / q;
            order[i] = fit.domHz / (rpm[i] / 60.0);
            double prior = kind == Kind.VIBRATION ? domPrior(order[i]) : spacingPrior(q);
            double s = prior * Math.max(0.05, firingShare(fit, q)) * rangePrior(rpm[i]);
            if (s > 0 && support != null) {
                double w = 0;
                for (double[] e : support) {
                    if (e[0] > 0 && Math.abs(Math.log(rpm[i] / e[0])) < 0.025) w = Math.max(w, e[1]);
                }
                s *= 1 + SUPPORT_BOOST * w;
            }
            plain[i] = s;
            maxPlain = Math.max(maxPlain, s);
            if (s > 0 && !Double.isNaN(lastRpm) && tSeconds - lastT < 1.5
                    && Math.abs(Math.log(rpm[i] / lastRpm)) < 0.10) s *= STICKY;
            if (s > 0 && isLocked() && Math.abs(Math.log(rpm[i] / lockRpm)) < 0.20
                    && (Double.isNaN(lockOrder) || Math.abs(order[i] - lockOrder) < 0.26)) {
                s *= LOCK_BOOST;
                boosted[i] = true;
            }
            score[i] = s;
            if (s > 0 && (best < 0 || s > score[best])) best = i;
        }
        if (best < 0) return null;

        // Other readings worth offering, judged without the bonuses, best first.
        double second = 0;
        List<double[]> ranked = new ArrayList<>();
        for (int i = 0; i < nq; i++) {
            if (i == best || score[i] <= 0) continue;
            second = Math.max(second, score[i]);
            if (plain[i] >= OFFER * maxPlain) ranked.add(new double[]{rpm[i], plain[i]});
        }
        Collections.sort(ranked, (a, b) -> Double.compare(b[1], a[1]));
        List<Double> alts = new ArrayList<>();
        for (double[] r : ranked) alts.add(r[0]);
        final double chosen = rpm[best];
        boolean locked = boosted[best];
        boolean ambiguous = !locked && second / score[best] > AMBIGUOUS;

        double conf = 0.5 * clamp01((fit.coverage - MIN_COVERAGE) / (1 - MIN_COVERAGE))
                + 0.3 * clamp01((fit.domSnr - MIN_SNR) / 18.0)
                + 0.2 * clamp01((fit.matched - 1) / 2.0);
        if (ambiguous) conf *= 0.7;
        if (locked) {
            conf = Math.max(conf, 0.6);
            lockRpm = chosen; // the lock follows the engine
            lockT = tSeconds;
        }
        lastRpm = chosen;
        lastT = tSeconds;
        return new Estimate(chosen, fit.spacing, fit.coverage, fit.matched, conf, ambiguous, locked,
                fit.domHz, order[best], alts);
    }

    /**
     * Sharpens a reading against a second, finer spectrum: a weighted
     * least-squares line through every peak that sits on an engine order.
     *
     * @return the refined rpm, or the input when nothing lines up
     */
    public static double refine(double rpm, List<Analysis.Peak> peaks, double df) {
        if (rpm <= 0 || peaks == null) return rpm;
        double crank = rpm / 60.0;
        double num = 0, den = 0;
        for (Analysis.Peak p : peaks) {
            double o = Math.round(p.hz / crank * 2) / 2.0;
            if (o < 0.5 || o > 12) continue;
            if (Math.abs(p.hz - o * crank) > Math.max(0.6 * df, 0.012 * p.hz)) continue;
            // Firing harmonics carry the reading; half orders only help a little.
            double w = p.amp * (o % 2 == 0 ? 1.0 : 0.5);
            num += w * o * p.hz;
            den += w * o * o;
        }
        if (den <= 0) return rpm;
        double refined = 60.0 * num / den;
        return Math.abs(refined / rpm - 1) < 0.02 ? refined : rpm;
    }

    // ------------------------------------------------------------------ series fit

    static Fit fit(List<Analysis.Peak> peaks, double df, double[] excludeHz, double excludeTol, double minAmp) {
        if (peaks == null || peaks.isEmpty()) return null;
        List<Analysis.Peak> usable = new ArrayList<>();
        double maxAmp = 0;
        for (Analysis.Peak p : peaks) {
            boolean skip = false;
            if (excludeHz != null) {
                for (double e : excludeHz) {
                    if (e > 0 && Math.abs(p.hz - e) <= Math.max(excludeTol * e, 1.2 * df)) { skip = true; break; }
                }
            }
            if (skip) continue;
            usable.add(p);
            maxAmp = Math.max(maxAmp, p.amp);
        }
        if (maxAmp < minAmp) return null;

        List<Analysis.Peak> kept = new ArrayList<>();
        double total = 0;
        for (Analysis.Peak p : usable) {
            if (p.amp >= maxAmp * KEEP_FRACTION) {
                kept.add(p);
                total += p.amp;
            }
        }

        // Candidate spacings: each peak divided by 1..8.
        List<Double> cands = new ArrayList<>();
        for (Analysis.Peak p : kept) {
            for (int n = 1; n <= 8; n++) {
                double f = p.hz / n;
                if (f >= 4.5) cands.add(f);
            }
        }
        Collections.sort(cands, Collections.reverseOrder());

        // Score = share of peak energy on the comb, less what a comb this fine
        // would catch by luck. Among near-best combs the coarsest one is the series.
        double tolMid = tol(60, df);
        List<double[]> scored = new ArrayList<>();
        double bestScore = 0;
        double prev = Double.MAX_VALUE;
        for (double f : cands) {
            if (prev - f < 0.05) continue;
            prev = f;
            double c = coverage(kept, f, df) / total;
            if (!plausibleComb(kept, f, df)) continue;
            double s = c * (1 - Math.min(0.9, 2 * tolMid / f));
            scored.add(new double[]{f, c, s});
            bestScore = Math.max(bestScore, s);
        }
        double spacing = Double.NaN, cov = 0;
        for (double[] e : scored) {
            if (e[1] >= MIN_COVERAGE && e[2] >= NEAR_BEST * bestScore) {
                spacing = e[0];
                cov = e[1];
                break;
            }
        }
        if (Double.isNaN(spacing)) return null;

        // Refine with a weighted least-squares fit through the matched peaks.
        double num = 0, den = 0;
        for (Analysis.Peak p : kept) {
            int n = (int) Math.round(p.hz / spacing);
            if (n < 1 || n > 24 || Math.abs(p.hz - n * spacing) > tol(p.hz, df)) continue;
            num += p.amp * n * p.hz;
            den += p.amp * (double) n * n;
        }
        if (den <= 0) return null;
        spacing = num / den;

        Fit fit = new Fit();
        fit.spacing = spacing;
        fit.coverage = cov;
        for (Analysis.Peak p : kept) {
            int n = (int) Math.round(p.hz / spacing);
            if (n < 1 || n > 24 || Math.abs(p.hz - n * spacing) > tol(p.hz, df)) continue;
            fit.onSeries.add(p);
            fit.harmonic.add(n);
            fit.matchedAmp += p.amp;
            fit.matched++;
            if (p.amp > fit.domAmp) {
                fit.domAmp = p.amp;
                fit.domHz = p.hz;
                fit.domSnr = p.snr;
            }
        }
        if (fit.matched == 0 || fit.domSnr < MIN_SNR) return null;
        return fit;
    }

    private static double coverage(List<Analysis.Peak> peaks, double f, double df) {
        double c = 0;
        for (Analysis.Peak p : peaks) {
            int n = (int) Math.round(p.hz / f);
            if (n >= 1 && n <= 24 && Math.abs(p.hz - n * f) <= tol(p.hz, df)) c += p.amp;
        }
        return c;
    }

    /**
     * A real engine series fills its low teeth: the first or second harmonic of
     * the spacing is present, or at least half the teeth up to the highest one
     * are. A fine comb that only catches scattered peaks by luck fails this.
     */
    private static boolean plausibleComb(List<Analysis.Peak> peaks, double f, double df) {
        boolean[] tooth = new boolean[25];
        int top = 0, filled = 0;
        for (Analysis.Peak p : peaks) {
            int n = (int) Math.round(p.hz / f);
            if (n >= 1 && n <= 24 && Math.abs(p.hz - n * f) <= tol(p.hz, df) && !tooth[n]) {
                tooth[n] = true;
                filled++;
                top = Math.max(top, n);
            }
        }
        if (filled == 0) return false;
        return tooth[1] || tooth[2] || filled * 2 >= top;
    }

    private static double tol(double hz, double df) {
        return Math.max(0.5 * df, 0.008 * hz);
    }

    /** Share of the series' energy that sits on firing harmonics (2nd, 4th, 6th…) if the spacing is order q. */
    private static double firingShare(Fit fit, double q) {
        if (fit.matchedAmp <= 0) return 0;
        double on = 0;
        for (int i = 0; i < fit.onSeries.size(); i++) {
            double o = fit.harmonic.get(i) * q;
            if (Math.abs(o / 2 - Math.round(o / 2)) < 1e-6) on += fit.onSeries.get(i).amp;
        }
        return on / fit.matchedAmp;
    }

    /** How likely the strongest engine peak is to be this order, for a four-cylinder. */
    private static double domPrior(double order) {
        double o = Math.round(order * 2) / 2.0;
        if (o == 2) return 1.0;
        if (o == 4) return 0.40;
        if (o == 1) return 0.25;
        if (o == 6) return 0.15;
        if (o == 8 || o == 0.5) return 0.08;
        if (o == 1.5 || o == 3) return 0.06;
        return 0.03;
    }

    /** For sound: how likely the series spacing is to be this engine order. */
    private static double spacingPrior(double q) {
        if (q == 2) return 1.0;
        if (q == 1) return 0.45;
        if (q == 4) return 0.25;
        return 0.15;
    }

    private static double rangePrior(double rpm) {
        if (rpm < 450 || rpm > 7500) return 0;
        if (rpm < 550) return 0.3;
        if (rpm > 5000) return 0.4;
        return 1;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
