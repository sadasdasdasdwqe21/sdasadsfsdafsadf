package app.shakefinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Turns raw accelerometer samples (uneven timestamps, three axes) into an
 * amplitude spectrum, then finds the peaks that stand clear of the noise.
 *
 * Plain Java with no Android imports, so it can be unit tested on a PC.
 */
public final class Analysis {

    private Analysis() {}

    /** Amplitude spectrum. amp[k] is the vibration amplitude in m/s² at k * df Hz. */
    public static final class Spectrum {
        public final double fs;       // sample rate the phone actually delivered, Hz
        public final double df;       // frequency resolution, Hz per bin
        public final double[] amp;    // all three axes combined
        public final double[] vert;   // the part along gravity (up-down), or null if unknown
        public final double floor;    // typical noise level between the peaks, m/s²
        public final double drift;    // how far the strongest peak moved during the window (0.01 = 1%), NaN if unknown
        public final int segments;    // averaged blocks
        public final int gaps;        // sensor dropouts inside the window
        public final double seconds;  // length of data analysed

        Spectrum(double fs, double df, double[] amp, double[] vert, double floor, double drift,
                 int segments, int gaps, double seconds) {
            this.fs = fs;
            this.df = df;
            this.amp = amp;
            this.vert = vert;
            this.floor = floor;
            this.drift = drift;
            this.segments = segments;
            this.gaps = gaps;
            this.seconds = seconds;
        }

        /** Amplitude at any frequency, read between the bins. */
        public double at(double hz) {
            double x = hz / df;
            int k = (int) Math.floor(x);
            if (k < 0 || k + 1 >= amp.length) return 0;
            double a = x - k;
            return amp[k] * (1 - a) + amp[k + 1] * a;
        }
    }

    public static final class Peak {
        public final double hz;
        public final double amp;      // m/s²
        public final double snr;      // amp divided by the noise floor
        public final double vertFrac; // share of the motion that is up-down (0..1), NaN if unknown

        Peak(double hz, double amp, double snr, double vertFrac) {
            this.hz = hz;
            this.amp = amp;
            this.snr = snr;
            this.vertFrac = vertFrac;
        }
    }

    /**
     * Welch-averaged spectrum of the vector sum of the three axes.
     *
     * @param tNs        sensor timestamps in nanoseconds, oldest first
     * @param n          number of valid samples in the arrays
     * @param segSeconds wanted length of each averaged block; longer is sharper but slower to react
     * @return the spectrum, or null if there is not enough data yet
     */
    public static Spectrum welch(long[] tNs, float[] x, float[] y, float[] z, int n, double segSeconds) {
        if (n < 96) return null;
        double dur = (tNs[n - 1] - tNs[0]) / 1e9;
        if (dur < 0.4) return null;
        double dtNom = dur / (n - 1);

        // 1. Put the samples on an even time grid.
        //    The sensor chip samples on its own steady clock; the timestamps
        //    Android attaches wobble around that. So when no sample is missing,
        //    the samples are already evenly spaced and are used exactly as they
        //    came. Re-timing them by their wobbly timestamps would only blur the
        //    high frequencies.
        boolean regular = true;
        long t0 = tNs[0];
        for (int i = 1; i < n; i++) {
            double dev = (tNs[i] - t0) / 1e9 - i * dtNom;
            if (dev > 0.75 * dtNom || dev < -0.75 * dtNom) { regular = false; break; }
        }
        int m;
        int gaps = 0;
        double fs;
        double[][] u;
        if (regular) {
            m = n;
            fs = 1.0 / dtNom;
            u = new double[3][m];
            for (int i = 0; i < n; i++) { u[0][i] = x[i]; u[1][i] = y[i]; u[2][i] = z[i]; }
        } else {
            // Samples were dropped. Count them, give every sample its true slot
            // and fill each hole from its neighbours.
            for (int i = 1; i < n; i++) {
                double d = (tNs[i] - tNs[i - 1]) / 1e9;
                if (d > 1.6 * dtNom) gaps += (int) Math.round(d / dtNom) - 1;
            }
            double dt = dur / (n - 1 + gaps);
            int[] slot = new int[n];
            for (int i = 1; i < n; i++) {
                int k = (int) Math.round((tNs[i] - t0) / 1e9 / dt);
                slot[i] = Math.max(slot[i - 1] + 1, k);
            }
            m = slot[n - 1] + 1;
            if (m > 4 * n) return null; // timestamps make no sense
            fs = (m - 1) / dur;
            u = new double[3][m];
            for (int i = 0; i < n; i++) {
                int a = slot[i];
                u[0][a] = x[i]; u[1][a] = y[i]; u[2][a] = z[i];
                if (i + 1 >= n) break;
                int b = slot[i + 1];
                for (int sIdx = a + 1; sIdx < b; sIdx++) {
                    boolean cubic = i >= 1 && i + 2 < n && b - a <= 3
                            && a - slot[i - 1] <= 2 && slot[i + 2] - b <= 2;
                    if (cubic) {
                        double pa = slot[i - 1], pb = a, pc = b, pd = slot[i + 2], tt = sIdx;
                        double w0 = (tt - pb) * (tt - pc) * (tt - pd) / ((pa - pb) * (pa - pc) * (pa - pd));
                        double w1 = (tt - pa) * (tt - pc) * (tt - pd) / ((pb - pa) * (pb - pc) * (pb - pd));
                        double w2 = (tt - pa) * (tt - pb) * (tt - pd) / ((pc - pa) * (pc - pb) * (pc - pd));
                        double w3 = (tt - pa) * (tt - pb) * (tt - pc) / ((pd - pa) * (pd - pb) * (pd - pc));
                        u[0][sIdx] = w0 * x[i - 1] + w1 * x[i] + w2 * x[i + 1] + w3 * x[i + 2];
                        u[1][sIdx] = w0 * y[i - 1] + w1 * y[i] + w2 * y[i + 1] + w3 * y[i + 2];
                        u[2][sIdx] = w0 * z[i - 1] + w1 * z[i] + w2 * z[i + 1] + w3 * z[i + 2];
                    } else {
                        double f = (sIdx - a) / (double) (b - a);
                        u[0][sIdx] = x[i] + (x[i + 1] - x[i]) * f;
                        u[1][sIdx] = y[i] + (y[i + 1] - y[i]) * f;
                        u[2][sIdx] = z[i] + (z[i + 1] - z[i]) * f;
                    }
                }
            }
        }

        // 2. Which way is down? The steady part of the signal is gravity.
        double gx = 0, gy = 0, gz = 0;
        for (int i = 0; i < m; i++) { gx += u[0][i]; gy += u[1][i]; gz += u[2][i]; }
        gx /= m; gy /= m; gz /= m;
        double gNorm = Math.sqrt(gx * gx + gy * gy + gz * gz);
        boolean haveDown = gNorm > 4.0;
        double[] down = haveDown ? new double[m] : null;
        if (haveDown) {
            gx /= gNorm; gy /= gNorm; gz /= gNorm;
            for (int i = 0; i < m; i++) down[i] = u[0][i] * gx + u[1][i] * gy + u[2][i] * gz;
        }

        // 3. Block length: the largest power of two near the wanted length.
        int len = 64;
        while (len * 2 <= m && len * 2 <= fs * segSeconds * 1.2) len *= 2;
        if (len > m) return null;

        double[] w = new double[len];
        double wsum = 0;
        for (int k = 0; k < len; k++) {
            w[k] = 0.5 - 0.5 * Math.cos(2 * Math.PI * k / len); // periodic Hann
            wsum += w[k];
        }

        // 4. Welch: 50% overlapping Hann-windowed blocks, power summed over axes.
        //    Blocks are laid out from the newest sample backwards.
        int half = len / 2;
        int segs = 1 + (m - len) / half;
        double[] power = new double[half + 1];
        double[] vPower = haveDown ? new double[half + 1] : null;
        double[][] segPower = new double[segs][];
        double[] re = new double[len];
        double[] im = new double[len];
        for (int sIdx = 0; sIdx < segs; sIdx++) {
            int s = (m - len) - sIdx * half;
            double[] sp = new double[half + 1];
            for (int ax = 0; ax < (haveDown ? 4 : 3); ax++) {
                double[] src = ax < 3 ? u[ax] : down;
                double mean = 0;
                for (int k = 0; k < len; k++) mean += src[s + k];
                mean /= len; // removes gravity and slow tilt
                for (int k = 0; k < len; k++) {
                    re[k] = (src[s + k] - mean) * w[k];
                    im[k] = 0;
                }
                fft(re, im);
                if (ax < 3) {
                    for (int k = 0; k <= half; k++) sp[k] += re[k] * re[k] + im[k] * im[k];
                } else {
                    for (int k = 0; k <= half; k++) vPower[k] += re[k] * re[k] + im[k] * im[k];
                }
            }
            for (int k = 0; k <= half; k++) power[k] += sp[k];
            segPower[sIdx] = sp;
        }

        double df = fs / len;
        double scale = 2.0 / wsum;
        double[] amp = new double[half + 1];
        double[] vert = haveDown ? new double[half + 1] : null;
        for (int k = 0; k <= half; k++) {
            amp[k] = Math.sqrt(power[k] / segs) * scale;
            if (haveDown) vert[k] = Math.sqrt(vPower[k] / segs) * scale;
        }

        // 5. Noise floor: the median level between 4 Hz and the top of the usable band.
        int kLo = Math.max(1, (int) Math.ceil(4 / df));
        int kHi = Math.min(half, (int) Math.floor(Math.min(fs * 0.45, 200) / df));
        double floor = 0;
        if (kHi > kLo + 4) {
            double[] sorted = Arrays.copyOfRange(amp, kLo, kHi + 1);
            Arrays.sort(sorted);
            floor = sorted[sorted.length / 2];
        }

        // 6. Did the strongest peak move while we were measuring?
        double drift = Double.NaN;
        if (segs >= 2 && kHi > kLo + 4) {
            int kd = kLo;
            for (int k = kLo; k <= kHi; k++) if (amp[k] > amp[kd]) kd = k;
            if (amp[kd] > 6 * floor) {
                int span = Math.max(3, (int) Math.round(kd * 0.05));
                double fMin = Double.MAX_VALUE, fMax = 0;
                for (double[] sp : segPower) {
                    int a = Math.max(1, kd - span), b = Math.min(half - 1, kd + span);
                    int kb = a;
                    for (int k = a; k <= b; k++) if (sp[k] > sp[kb]) kb = k;
                    double f = (kb + hannOffset(Math.sqrt(sp[kb - 1]), Math.sqrt(sp[kb]),
                            Math.sqrt(sp[Math.min(half, kb + 1)]))) * df;
                    fMin = Math.min(fMin, f);
                    fMax = Math.max(fMax, f);
                }
                drift = (fMax - fMin) / (kd * df);
            }
        }
        return new Spectrum(fs, df, amp, vert, floor, drift, segs, gaps, m / fs);
    }

    /**
     * Where a tone really sits between FFT bins, for a Hann window.
     * Exact for a clean tone, unlike the usual parabola fit.
     *
     * @return offset in bins from the centre bin, between -0.5 and 0.5
     */
    static double hannOffset(double l, double c, double r) {
        if (c <= 0) return 0;
        if (r >= l) {
            double a = r / c;
            double d = (2 * a - 1) / (a + 1);
            return d < 0 ? 0 : Math.min(d, 0.5);
        } else {
            double a = l / c;
            double d = (2 * a - 1) / (a + 1);
            return d < 0 ? 0 : -Math.min(d, 0.5);
        }
    }

    /** How much a Hann window under-reads a tone that sits d bins off centre. */
    static double hannGain(double d) {
        double ad = Math.abs(d);
        if (ad < 1e-6) return 1;
        return Math.sin(Math.PI * ad) / (Math.PI * ad * (1 - ad * ad));
    }

    /**
     * Peaks that stand clear of the noise between fLo and fHi, largest first.
     * A peak must be at least 4 times the noise floor and above minAmp.
     */
    public static List<Peak> peaks(Spectrum sp, double fLo, double fHi, int count, double minAmp) {
        List<Peak> out = new ArrayList<>();
        if (sp == null) return out;
        double[] a = sp.amp;
        int kLo = Math.max(2, (int) Math.ceil(fLo / sp.df));
        int kHi = Math.min(a.length - 2, (int) Math.floor(fHi / sp.df));
        double need = Math.max(minAmp, 4 * sp.floor);
        List<Peak> cands = new ArrayList<>();
        for (int k = kLo; k <= kHi; k++) {
            if (a[k] > a[k - 1] && a[k] >= a[k + 1]) {
                double d = hannOffset(a[k - 1], a[k], a[k + 1]);
                double amp = a[k] / hannGain(d);
                if (amp < need) continue;
                double vf = Double.NaN;
                if (sp.vert != null) {
                    double tot = 0, v = 0;
                    for (int q = k - 1; q <= k + 1; q++) {
                        tot += a[q] * a[q];
                        v += sp.vert[q] * sp.vert[q];
                    }
                    if (tot > 0) vf = Math.max(0, Math.min(1, v / tot));
                }
                cands.add(new Peak((k + d) * sp.df, amp, sp.floor > 0 ? amp / sp.floor : 999, vf));
            }
        }
        Collections.sort(cands, (p1, p2) -> Double.compare(p2.amp, p1.amp));
        double minSep = Math.max(3 * sp.df, 1.0);
        for (Peak p : cands) {
            boolean clash = false;
            for (Peak q : out) if (Math.abs(q.hz - p.hz) < minSep) { clash = true; break; }
            if (!clash) out.add(p);
            if (out.size() >= count) break;
        }
        return out;
    }

    /**
     * Strength of the tone nearest a given frequency, in m/s². Looks within
     * relTol either side (never less than one and a half bins). Returns 0 when
     * nothing there stands at least 3 times above the noise.
     */
    public static double ampNear(Spectrum sp, double hz, double relTol) {
        if (sp == null || !(hz > 0)) return 0;
        double[] a = sp.amp;
        double w = Math.max(1.5 * sp.df, relTol * hz);
        int kLo = Math.max(1, (int) Math.floor((hz - w) / sp.df));
        int kHi = Math.min(a.length - 2, (int) Math.ceil((hz + w) / sp.df));
        if (kHi < kLo) return 0;
        int kb = kLo;
        for (int k = kLo; k <= kHi; k++) if (a[k] > a[kb]) kb = k;
        double amp = a[kb] / hannGain(hannOffset(a[kb - 1], a[kb], a[kb + 1]));
        return amp >= 3 * sp.floor ? amp : 0;
    }

    /** In-place iterative radix-2 FFT. Length must be a power of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            double ang = -2 * Math.PI / size;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int start = 0; start < n; start += size) {
                double cr = 1, ci = 0;
                for (int k = 0; k < size / 2; k++) {
                    int a = start + k, b = a + size / 2;
                    double tr = re[b] * cr - im[b] * ci;
                    double ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti;
                    re[a] += tr; im[a] += ti;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
