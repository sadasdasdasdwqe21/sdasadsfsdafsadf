package app.shakefinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns raw accelerometer samples (uneven timestamps, three axes) into an
 * amplitude spectrum, then finds the strongest peaks.
 *
 * Plain Java with no Android imports, so it can be unit tested on a PC.
 */
public final class Analysis {

    private Analysis() {}

    /** Amplitude spectrum. amp[k] is the vibration amplitude in m/s² at k * df Hz. */
    public static final class Spectrum {
        public final double fs;   // sample rate the phone actually delivered, Hz
        public final double df;   // frequency resolution, Hz per bin
        public final double[] amp;

        Spectrum(double fs, double df, double[] amp) {
            this.fs = fs;
            this.df = df;
            this.amp = amp;
        }
    }

    public static final class Peak {
        public final double hz;
        public final double amp; // m/s²

        Peak(double hz, double amp) {
            this.hz = hz;
            this.amp = amp;
        }
    }

    /**
     * Welch-averaged spectrum of the vector sum of the three axes.
     *
     * @param tNs sensor timestamps in nanoseconds, oldest first
     * @param n   number of valid samples in the arrays
     * @return the spectrum, or null if there is not enough data yet
     */
    public static Spectrum welch(long[] tNs, float[] x, float[] y, float[] z, int n) {
        if (n < 128) return null;
        double dur = (tNs[n - 1] - tNs[0]) / 1e9;
        if (dur < 0.5) return null;
        double fs = (n - 1) / dur;

        // 1. Resample onto an even time grid (Android delivers jittery timestamps).
        int m = (int) Math.floor(dur * fs) + 1;
        double[][] u = new double[3][m];
        int j = 0;
        long t0 = tNs[0];
        for (int i = 0; i < m; i++) {
            double tt = t0 + i / fs * 1e9;
            while (j < n - 2 && tNs[j + 1] < tt) j++;
            double ta = tNs[j];
            double tb = tNs[j + 1];
            double a = tb > ta ? (tt - ta) / (tb - ta) : 0;
            if (a < 0) a = 0;
            if (a > 1) a = 1;
            u[0][i] = x[j] + (x[j + 1] - x[j]) * a;
            u[1][i] = y[j] + (y[j + 1] - y[j]) * a;
            u[2][i] = z[j] + (z[j + 1] - z[j]) * a;
        }

        // 2. Segment length: power of two giving roughly 0.4 Hz resolution.
        double target = fs * 2.5;
        int len = 128;
        while (len * 2 <= m && len * 2 <= target * 1.5) len *= 2;
        if (len > m) return null;

        double[] w = new double[len];
        double wsum = 0;
        for (int k = 0; k < len; k++) {
            w[k] = 0.5 - 0.5 * Math.cos(2 * Math.PI * k / (len - 1));
            wsum += w[k];
        }

        // 3. Welch: 50% overlapping Hann-windowed segments, power summed over axes.
        int half = len / 2;
        double[] power = new double[half + 1];
        double[] re = new double[len];
        double[] im = new double[len];
        int segs = 0;
        for (int s = 0; s + len <= m; s += half) {
            for (int ax = 0; ax < 3; ax++) {
                double mean = 0;
                for (int k = 0; k < len; k++) mean += u[ax][s + k];
                mean /= len; // removes gravity and slow tilt
                for (int k = 0; k < len; k++) {
                    re[k] = (u[ax][s + k] - mean) * w[k];
                    im[k] = 0;
                }
                fft(re, im);
                for (int k = 0; k <= half; k++) power[k] += re[k] * re[k] + im[k] * im[k];
            }
            segs++;
        }
        if (segs == 0) return null;

        double[] amp = new double[half + 1];
        for (int k = 0; k <= half; k++) amp[k] = Math.sqrt(power[k] / segs) * 2.0 / wsum;
        return new Spectrum(fs, fs / len, amp);
    }

    /** Strongest local maxima between fLo and fHi, largest first. */
    public static List<Peak> peaks(Spectrum sp, double fLo, double fHi, int count) {
        List<Peak> out = new ArrayList<>();
        if (sp == null) return out;
        double[] a = sp.amp;
        int kLo = Math.max(2, (int) Math.ceil(fLo / sp.df));
        int kHi = Math.min(a.length - 2, (int) Math.floor(fHi / sp.df));
        List<Peak> cands = new ArrayList<>();
        for (int k = kLo; k <= kHi; k++) {
            if (a[k] > a[k - 1] && a[k] >= a[k + 1]) {
                double l = a[k - 1], c = a[k], r = a[k + 1];
                double den = l - 2 * c + r;
                double p = den != 0 ? 0.5 * (l - r) / den : 0;
                if (p < -0.5) p = -0.5;
                if (p > 0.5) p = 0.5;
                cands.add(new Peak((k + p) * sp.df, c - 0.25 * (l - r) * p));
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
