package app.shakefinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Listens to the engine. Microphone samples come in at 44.1 or 48 kHz; engine
 * notes live below about 700 Hz, so the stream is filtered and thinned to
 * roughly 3 kHz and the last 1.4 seconds are kept. From that comes a sound
 * spectrum and the tones that stand out of the background.
 *
 * A phone microphone is not calibrated, so levels are given in decibels
 * relative to the loudest sound the microphone can take (dBFS) and, for peaks,
 * as "times louder than the background around it".
 *
 * Plain Java, no Android imports.
 */
public final class SoundRpm {

    private static final int DECIM = 16;
    private static final int N = 4096;
    public static final double F_LO = 18, F_HI = 700;

    /** Sound spectrum. db[k] is the level in dBFS at k * df Hz. */
    public static final class Sound {
        public final double df;
        public final double[] amp;      // linear, 1.0 = full scale
        public final double[] floor;    // background level around each bin, linear
        public final double levelDb;    // overall loudness of the window, dBFS
        public final boolean clipped;   // the microphone overloaded

        Sound(double df, double[] amp, double[] floor, double levelDb, boolean clipped) {
            this.df = df;
            this.amp = amp;
            this.floor = floor;
            this.levelDb = levelDb;
            this.clipped = clipped;
        }

        public double db(int k) {
            return 20 * Math.log10(Math.max(amp[k], 1e-9));
        }
    }

    private final double fs; // after thinning
    // Three running-sum stages, each as long as the thinning step, make the low-pass.
    private final long[][] hist = new long[3][DECIM];
    private final long[] sum = new long[3];
    private final int[] pos = new int[3];
    private int phase = 0;

    private final float[] ring = new float[N];
    private int head = 0;
    private long filled = 0;
    private int clipRun = 0;
    private long clipUntil = 0, total = 0;

    public SoundRpm(int sampleRate) {
        fs = sampleRate / (double) DECIM;
    }

    public double sampleRate() {
        return fs;
    }

    public synchronized void clear() {
        for (int s = 0; s < 3; s++) { Arrays.fill(hist[s], 0); sum[s] = 0; pos[s] = 0; }
        Arrays.fill(ring, 0);
        head = 0;
        filled = 0;
        phase = 0;
        clipUntil = 0;
        total = 0;
    }

    /** Feeds 16-bit microphone samples. Called from the recording thread. */
    public synchronized void feed(short[] pcm, int n) {
        final double norm = 1.0 / (32768.0 * DECIM * DECIM * DECIM);
        for (int i = 0; i < n; i++) {
            int v0 = pcm[i];
            total++;
            if (v0 > 32000 || v0 < -32000) {
                if (++clipRun >= 3) clipUntil = total + (long) (fs * DECIM * 2);
            } else clipRun = 0;
            long v = v0;
            for (int s = 0; s < 3; s++) {
                sum[s] += v - hist[s][pos[s]];
                hist[s][pos[s]] = v;
                pos[s] = (pos[s] + 1) % DECIM;
                v = sum[s];
            }
            if (++phase == DECIM) {
                phase = 0;
                ring[head] = (float) (v * norm);
                head = (head + 1) % N;
                filled++;
            }
        }
    }

    /** The spectrum of the last 1.4 seconds, or null until that much has been heard. */
    public synchronized Sound analyze() {
        if (filled < N) return null;
        double[] re = new double[N], im = new double[N];
        double mean = 0;
        for (int i = 0; i < N; i++) mean += ring[(head + i) % N];
        mean /= N;
        double wsum = 0, energy = 0;
        for (int i = 0; i < N; i++) {
            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N);
            double v = ring[(head + i) % N] - mean;
            energy += v * v;
            re[i] = v * w;
            wsum += w;
        }
        Analysis.fft(re, im);
        int half = N / 2;
        double df = fs / N;
        double[] amp = new double[half + 1];
        // Undo the droop of the thinning filter so levels are true across the band.
        for (int k = 0; k <= half; k++) {
            double a = Math.sqrt(re[k] * re[k] + im[k] * im[k]) * 2.0 / wsum;
            double x = Math.PI * k * df / (fs * DECIM) * DECIM; // = pi * f / fs
            double droop = 1;
            if (x > 1e-9) {
                double sinc = Math.sin(x) / (DECIM * Math.sin(x / DECIM));
                droop = Math.abs(sinc * sinc * sinc);
            }
            amp[k] = droop > 0.05 ? a / droop : a;
        }

        // Background around each bin: the median of its neighbourhood (about ±14 Hz).
        int kLo = Math.max(2, (int) Math.floor(F_LO / df)), kHi = Math.min(half - 2, (int) Math.ceil(F_HI / df));
        int span = Math.max(8, (int) Math.round(14 / df));
        double[] floor = new double[half + 1];
        double[] win = new double[2 * span + 1];
        int step = Math.max(1, span / 4);
        double last = 0;
        for (int k = kLo; k <= kHi; k++) {
            if ((k - kLo) % step == 0 || last == 0) {
                int a = Math.max(1, k - span), b = Math.min(half, k + span);
                int c = 0;
                for (int q = a; q <= b; q++) win[c++] = amp[q];
                Arrays.sort(win, 0, c);
                last = win[c / 2];
            }
            floor[k] = last;
        }
        double rms = Math.sqrt(energy / N);
        return new Sound(df, amp, floor, 20 * Math.log10(Math.max(rms, 1e-9)), total < clipUntil);
    }

    /**
     * Tones that stand at least 4.5 times (13 dB) above their background,
     * strongest first. Each peak's amp and snr are both that ratio.
     */
    public static List<Analysis.Peak> peaks(Sound s, int count) {
        List<Analysis.Peak> out = new ArrayList<>();
        if (s == null) return out;
        double[] a = s.amp;
        int kLo = Math.max(2, (int) Math.ceil(F_LO / s.df));
        int kHi = Math.min(a.length - 2, (int) Math.floor(F_HI / s.df));
        List<Analysis.Peak> cands = new ArrayList<>();
        for (int k = kLo; k <= kHi; k++) {
            if (a[k] > a[k - 1] && a[k] >= a[k + 1] && s.floor[k] > 0) {
                double d = Analysis.hannOffset(a[k - 1], a[k], a[k + 1]);
                double ratio = a[k] / Analysis.hannGain(d) / s.floor[k];
                if (ratio < 4.5) continue;
                cands.add(new Analysis.Peak((k + d) * s.df, ratio, ratio, Double.NaN));
            }
        }
        Collections.sort(cands, (p1, p2) -> Double.compare(p2.amp, p1.amp));
        double minSep = Math.max(3 * s.df, 2.0);
        for (Analysis.Peak p : cands) {
            boolean clash = false;
            for (Analysis.Peak q : out) if (Math.abs(q.hz - p.hz) < minSep) { clash = true; break; }
            if (!clash) out.add(p);
            if (out.size() >= count) break;
        }
        return out;
    }
}
