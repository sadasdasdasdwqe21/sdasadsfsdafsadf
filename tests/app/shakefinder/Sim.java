package app.shakefinder;

import java.util.*;

/** Simulated phone accelerometer + the same pipeline the app runs. Test code only. */
public class Sim {
    static final double G = 9.80665;
    final Random rnd;
    final double fs;
    final List<long[]> ts = new ArrayList<>();
    long[] t; float[] x, y, z; int n;
    double jitter = 0.20;      // timestamp wobble, fraction of dt (reporting only)
    double dropProb = 0.0;     // real dropped samples
    double clockErr = 1.007;   // chip clock vs nominal
    double noiseMg = 2.0;      // rms per axis, broadband
    double[] dir = {0.25, 0.35, 0.9}; // vibration direction in phone axes
    double[] grav = {0.3, 0.2, 9.79};

    interface Rpm { double at(double t); }
    interface Amp { double at(double order, double rpm, double t); }

    Sim(long seed, double fs) { rnd = new Random(seed); this.fs = fs; }

    /** orders[i] with amplitude ampMg[i] (may be modulated by amp), rpm as a function of time. */
    void generate(double seconds, Rpm rpm, double[] orders, double[] ampMg, Amp mod,
                  double[] extraHz, double[] extraMg, double roadMg) {
        int cap = (int) (seconds * fs * 1.05) + 16;
        t = new long[cap]; x = new float[cap]; y = new float[cap]; z = new float[cap]; n = 0;
        double dnorm = Math.sqrt(dir[0]*dir[0]+dir[1]*dir[1]+dir[2]*dir[2]);
        double[] phase = new double[orders.length];
        double[] ph0 = new double[orders.length];
        for (int i = 0; i < orders.length; i++) ph0[i] = rnd.nextDouble() * 2 * Math.PI;
        double[] eph = new double[extraHz == null ? 0 : extraHz.length];
        for (int i = 0; i < eph.length; i++) eph[i] = rnd.nextDouble() * 2 * Math.PI;
        // road noise: resonator near 12 Hz driven by white noise
        double r1 = 0, r2 = 0; double w0 = 2*Math.PI*12.3, zeta = 0.15;
        double crankAngle = 0; // in revolutions
        double sub = 8; double dts = 1.0 / (fs * clockErr) / sub;
        double tt = 0; double road = 0, roadV = 0;
        double nextSample = 0;
        while (tt < seconds) {
            double r = rpm.at(tt);
            crankAngle += r / 60.0 * dts;
            // road resonator (semi-implicit Euler)
            double drive = rnd.nextGaussian() * roadMg * 400;
            roadV += (-2*zeta*w0*roadV - w0*w0*road + drive) * dts;
            road += roadV * dts;
            if (tt >= nextSample) {
                double v = 0;
                for (int i = 0; i < orders.length; i++) {
                    double a = ampMg[i] * (mod == null ? 1 : mod.at(orders[i], r, tt));
                    v += a * Math.sin(2 * Math.PI * orders[i] * crankAngle + ph0[i]);
                }
                for (int i = 0; i < eph.length; i++) v += extraMg[i] * Math.sin(2*Math.PI*extraHz[i]*tt + eph[i]);
                v *= G / 1000.0;
                double rd = road * G / 1000.0;
                if (rnd.nextDouble() >= dropProb) {
                    t[n] = (long) ((tt + 5.0 + (rnd.nextDouble() - 0.5) * 2 * jitter / fs) * 1e9);
                    x[n] = (float) (grav[0] + v * dir[0]/dnorm + rnd.nextGaussian() * noiseMg * G/1000);
                    y[n] = (float) (grav[1] + v * dir[1]/dnorm + rnd.nextGaussian() * noiseMg * G/1000);
                    z[n] = (float) (grav[2] + v * dir[2]/dnorm + rd + rnd.nextGaussian() * noiseMg * G/1000);
                    n++;
                }
                nextSample += 1.0 / (fs * clockErr);
            }
            tt += dts;
        }
    }

    /** Copies samples in (tEnd - win, tEnd] to fresh arrays, like the app's snapshot. */
    int snap(double tEnd, double win, long[] st, float[] sx, float[] sy, float[] sz) {
        long e = (long) ((tEnd + 5.0) * 1e9), s = e - (long) (win * 1e9);
        int c = 0;
        for (int i = 0; i < n; i++) if (t[i] > s && t[i] <= e) { st[c] = t[i]; sx[c] = x[i]; sy[c] = y[i]; sz[c] = z[i]; c++; }
        return c;
    }

    static final double SLOW_WIN = 5.2, FAST_WIN = 2.0, SLOW_SEG = 2.5, FAST_SEG = 1.1;

    static class Frame { Analysis.Spectrum slow, fast; List<Analysis.Peak> ps, pf; RpmTracker.Estimate est; double rpm = Double.NaN; }

    Frame frame(RpmTracker tr, double tEnd, double[] exclude) {
        long[] st = new long[n]; float[] sx = new float[n], sy = new float[n], sz = new float[n];
        Frame f = new Frame();
        int c = snap(tEnd, SLOW_WIN, st, sx, sy, sz);
        f.slow = Analysis.welch(st, sx, sy, sz, c, SLOW_SEG);
        c = snap(tEnd, FAST_WIN, st, sx, sy, sz);
        f.fast = Analysis.welch(st, sx, sy, sz, c, FAST_SEG);
        if (f.slow == null || f.fast == null) return f;
        f.ps = Analysis.peaks(f.slow, 4, Math.min(f.slow.fs * 0.45, 200), 14, 0.006);
        f.pf = Analysis.peaks(f.fast, 4, Math.min(f.fast.fs * 0.45, 200), 14, 0.006);
        boolean moving = !Double.isNaN(f.fast.drift) && f.fast.drift > 0.04;
        f.est = moving ? null : tr.update(f.pf, f.fast.df, exclude, 0.035, tEnd, null);
        if (f.est != null) {
            f.rpm = f.est.rpm;
            boolean steady = Double.isNaN(f.slow.drift) || f.slow.drift < 0.02;
            if (steady) f.rpm = RpmTracker.refine(f.est.rpm, f.ps, f.slow.df);
        }
        return f;
    }
}
