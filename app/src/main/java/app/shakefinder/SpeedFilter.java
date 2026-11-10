package app.shakefinder;

import java.util.Arrays;

/**
 * Keeps the last few GPS speed fixes and answers "how fast is the car going".
 * GPS speed comes from the Doppler shift of the satellite signals, so it is
 * true road speed, unlike a speedometer that reads a few percent high.
 *
 * Plain Java, no Android imports.
 */
public final class SpeedFilter {

    private static final int CAP = 64;
    private static final double STALE_S = 3.5;      // no fix for this long means the signal is lost
    private static final double STANDSTILL_MS = 0.5; // below 1.8 km/h a parked car's GPS just wanders

    private final double[] t = new double[CAP];
    private final double[] v = new double[CAP];
    private final double[] acc = new double[CAP];
    private int head = 0, count = 0;

    public synchronized void clear() {
        head = 0;
        count = 0;
    }

    /**
     * @param tSec    time of the fix in seconds, on the same clock as the queries
     * @param speedMs speed in m/s
     * @param accMs   the receiver's own speed accuracy in m/s, or NaN if it gives none
     */
    public synchronized void add(double tSec, double speedMs, double accMs) {
        t[head] = tSec;
        v[head] = Math.max(0, speedMs);
        acc[head] = accMs;
        head = (head + 1) % CAP;
        if (count < CAP) count++;
    }

    private int idx(int back) { // 0 = newest
        return ((head - 1 - back) % CAP + CAP) % CAP;
    }

    /** Seconds since the newest fix, or a huge number when there is none. */
    public synchronized double ageSec(double nowSec) {
        return count == 0 ? 1e9 : nowSec - t[idx(0)];
    }

    public synchronized boolean hasFix(double nowSec) {
        return ageSec(nowSec) <= STALE_S;
    }

    /** Current speed in km/h: the median of the last three fixes. NaN when the signal is lost. */
    public synchronized double kmh(double nowSec) {
        if (!hasFix(nowSec)) return Double.NaN;
        double[] last = new double[3];
        int n = 0;
        for (int b = 0; b < count && n < 3; b++) {
            if (nowSec - t[idx(b)] > STALE_S) break;
            last[n++] = v[idx(b)];
        }
        double[] s = Arrays.copyOf(last, n);
        Arrays.sort(s);
        double med = s[n / 2];
        return med < STANDSTILL_MS ? 0 : med * 3.6;
    }

    /** Average speed in km/h over the last windowSec seconds. NaN when there is no fix in that time. */
    public synchronized double meanKmh(double nowSec, double windowSec) {
        if (!hasFix(nowSec)) return Double.NaN;
        double sum = 0;
        int n = 0;
        for (int b = 0; b < count; b++) {
            if (nowSec - t[idx(b)] > windowSec) break;
            sum += v[idx(b)];
            n++;
        }
        if (n == 0) return Double.NaN;
        double mean = sum / n;
        return mean < STANDSTILL_MS ? 0 : mean * 3.6;
    }

    /** Highest minus lowest speed in km/h over the last windowSec seconds. */
    public synchronized double spreadKmh(double nowSec, double windowSec) {
        double lo = Double.MAX_VALUE, hi = 0;
        int n = 0;
        for (int b = 0; b < count; b++) {
            if (nowSec - t[idx(b)] > windowSec) break;
            lo = Math.min(lo, v[idx(b)]);
            hi = Math.max(hi, v[idx(b)]);
            n++;
        }
        return n < 2 ? 0 : (hi - lo) * 3.6;
    }

    /** The receiver's stated speed accuracy in km/h for the newest fix, or NaN. */
    public synchronized double accuracyKmh(double nowSec) {
        if (!hasFix(nowSec)) return Double.NaN;
        double a = acc[idx(0)];
        return Double.isNaN(a) ? Double.NaN : a * 3.6;
    }
}
