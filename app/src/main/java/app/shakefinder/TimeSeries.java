package app.shakefinder;

/** The last few timed readings from a source such as the OBD adapter. Plain Java. */
public final class TimeSeries {

    private final double[] t, v;
    private int head = 0, count = 0;

    public TimeSeries(int capacity) {
        t = new double[capacity];
        v = new double[capacity];
    }

    public synchronized void clear() {
        head = 0;
        count = 0;
    }

    public synchronized void add(double tSec, double value) {
        t[head] = tSec;
        v[head] = value;
        head = (head + 1) % t.length;
        if (count < t.length) count++;
    }

    private int idx(int back) {
        return ((head - 1 - back) % t.length + t.length) % t.length;
    }

    /** Seconds since the newest reading, or a huge number when there is none. */
    public synchronized double ageSec(double nowSec) {
        return count == 0 ? 1e9 : nowSec - t[idx(0)];
    }

    /** Newest reading if it is younger than maxAgeSec, else NaN. */
    public synchronized double latest(double nowSec, double maxAgeSec) {
        return ageSec(nowSec) <= maxAgeSec ? v[idx(0)] : Double.NaN;
    }

    /** Average over the last windowSec seconds, or NaN when that span holds no reading. */
    public synchronized double mean(double nowSec, double windowSec) {
        double sum = 0;
        int n = 0;
        for (int b = 0; b < count; b++) {
            if (nowSec - t[idx(b)] > windowSec) break;
            sum += v[idx(b)];
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Highest minus lowest over the last windowSec seconds. */
    public synchronized double spread(double nowSec, double windowSec) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        int n = 0;
        for (int b = 0; b < count; b++) {
            if (nowSec - t[idx(b)] > windowSec) break;
            lo = Math.min(lo, v[idx(b)]);
            hi = Math.max(hi, v[idx(b)]);
            n++;
        }
        return n < 2 ? 0 : hi - lo;
    }
}
