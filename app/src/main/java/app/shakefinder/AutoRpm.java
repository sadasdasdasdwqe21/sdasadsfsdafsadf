package app.shakefinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Measures engine speed from the spectrum itself.
 *
 * An engine's peaks form one harmonic series. The series spacing is found as
 * the largest frequency that explains almost all of the peak energy. Which
 * engine order that spacing is (½, 1st, 2nd or 4th) can't be told from the
 * vibration alone: 700 rpm with even firing looks the same as 1400 rpm with
 * half orders. So the typed rpm only has to pick the right octave, which
 * any tachometer glance does. Plain Java, no Android imports.
 */
public final class AutoRpm {

    private AutoRpm() {}

    /** Orders the series spacing may correspond to. Octave-spaced on purpose. */
    private static final double[] SPACING_ORDERS = {0.5, 1, 2, 4};
    private static final double MIN_RPM = 300, MAX_RPM = 7500;
    private static final double MIN_AMP = 0.02;      // m/s², about 2 mg
    private static final double KEEP_FRACTION = 0.06; // ignore peaks below 6% of the largest
    private static final double COVERAGE = 0.85;     // series must explain 85% of peak energy

    public static final class Result {
        public final double rpm;          // chosen engine speed
        public final double spacingHz;    // measured harmonic spacing
        public final int matchedPeaks;    // peaks on the series
        public final List<Double> others; // the same series read one octave up or down

        Result(double rpm, double spacingHz, int matchedPeaks, List<Double> others) {
            this.rpm = rpm;
            this.spacingHz = spacingHz;
            this.matchedPeaks = matchedPeaks;
            this.others = others;
        }
    }

    /**
     * @param peaks   spectrum peaks, any order
     * @param hintRpm the rpm the user typed; only needs to be within about ±40%
     * @param df      spectrum resolution in Hz
     * @return the estimate, or null when there is too little vibration to read
     */
    public static Result estimate(List<Analysis.Peak> peaks, double hintRpm, double df) {
        if (peaks == null || peaks.isEmpty()) return null;
        double maxAmp = 0;
        for (Analysis.Peak p : peaks) maxAmp = Math.max(maxAmp, p.amp);
        if (maxAmp < MIN_AMP) return null;

        List<Analysis.Peak> kept = new ArrayList<>();
        double total = 0;
        for (Analysis.Peak p : peaks) {
            if (p.amp >= maxAmp * KEEP_FRACTION) {
                kept.add(p);
                total += p.amp;
            }
        }

        // Candidate spacings: each peak divided by 1..10, largest first.
        List<Double> cands = new ArrayList<>();
        for (Analysis.Peak p : kept) {
            for (int n = 1; n <= 10; n++) {
                double f = p.hz / n;
                if (f >= 3.0) cands.add(f);
            }
        }
        Collections.sort(cands, Collections.reverseOrder());

        double spacing = Double.NaN;
        double prev = Double.MAX_VALUE;
        for (double f : cands) {
            if (prev - f < 0.05) continue; // duplicate
            prev = f;
            if (coverage(kept, f, df) >= COVERAGE * total) {
                spacing = f;
                break;
            }
        }
        if (Double.isNaN(spacing)) return null;

        // Refine with a weighted least-squares fit through the matched peaks.
        double num = 0, den = 0;
        int matched = 0;
        for (Analysis.Peak p : kept) {
            int n = (int) Math.round(p.hz / spacing);
            if (n < 1 || Math.abs(p.hz - n * spacing) > tol(p.hz, df)) continue;
            num += p.amp * n * p.hz;
            den += p.amp * (double) n * n;
            matched++;
        }
        if (den > 0) spacing = num / den;

        List<Double> options = new ArrayList<>();
        for (double q : SPACING_ORDERS) {
            double rpm = 60.0 * spacing / q;
            if (rpm >= MIN_RPM && rpm <= MAX_RPM) options.add(rpm);
        }
        if (options.isEmpty()) return null;

        double hint = hintRpm > 0 ? hintRpm : 60.0 * spacing / 2; // no hint: assume firing order
        double best = options.get(0);
        for (double r : options) {
            if (Math.abs(Math.log(r / hint)) < Math.abs(Math.log(best / hint))) best = r;
        }
        List<Double> others = new ArrayList<>();
        for (double r : options) {
            if (Math.abs(r - best * 0.5) < 1 || Math.abs(r - best * 2) < 1) others.add(r);
        }
        return new Result(best, spacing, matched, others);
    }

    private static double coverage(List<Analysis.Peak> peaks, double f, double df) {
        double c = 0;
        for (Analysis.Peak p : peaks) {
            int n = (int) Math.round(p.hz / f);
            if (n >= 1 && Math.abs(p.hz - n * f) <= tol(p.hz, df)) c += p.amp;
        }
        return c;
    }

    private static double tol(double hz, double df) {
        return Math.max(1.2 * df, 0.015 * hz);
    }
}
