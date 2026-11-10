package app.shakefinder;

import java.util.Locale;

/**
 * Shake strength against engine speed, built up while the engine is revved
 * slowly through its range. Each 50 rpm slot keeps the strongest reading seen
 * for the ½, 1st, 2nd and 4th engine orders and for the strongest peak of any
 * kind. A part that resonates shows up as a hump at one rpm.
 *
 * Plain Java, no Android imports.
 */
public final class SweepMap {

    public static final double BIN = 50, LO = 400, HI = 6500;
    public static final int N = (int) ((HI - LO) / BIN);
    /** Series: the four engine orders, then the strongest peak of any kind. */
    public static final double[] ORDERS = {0.5, 1, 2, 4};
    public static final String[] NAMES = {"½ order", "1st order", "2nd order", "4th order", "strongest peak"};
    public static final String[] KEYS = {"half", "first", "second", "fourth", "peak"};
    public static final int SERIES = 5, PEAK = 4;

    private final float[][] max = new float[SERIES][N];
    private final int[] hits = new int[N];

    public static int binOf(double rpm) {
        if (!(rpm >= LO) || rpm >= HI) return -1;
        return (int) ((rpm - LO) / BIN);
    }

    /** The middle of a slot, in rpm. */
    public static double rpmOf(int bin) {
        return LO + (bin + 0.5) * BIN;
    }

    /**
     * @param orderMg strength at the ½, 1st, 2nd and 4th orders, in mg
     * @param peakMg  the strongest peak of any kind, in mg
     */
    public void add(double rpm, double[] orderMg, double peakMg) {
        int b = binOf(rpm);
        if (b < 0) return;
        hits[b]++;
        for (int s = 0; s < 4; s++) if (orderMg[s] > max[s][b]) max[s][b] = (float) orderMg[s];
        if (peakMg > max[PEAK][b]) max[PEAK][b] = (float) peakMg;
    }

    public void clear() {
        for (float[] row : max) java.util.Arrays.fill(row, 0);
        java.util.Arrays.fill(hits, 0);
    }

    public double mg(int series, int bin) {
        return max[series][bin];
    }

    public boolean has(int bin) {
        return hits[bin] > 0;
    }

    public boolean isEmpty() {
        return firstBin() < 0;
    }

    public int firstBin() {
        for (int b = 0; b < N; b++) if (hits[b] > 0) return b;
        return -1;
    }

    public int lastBin() {
        for (int b = N - 1; b >= 0; b--) if (hits[b] > 0) return b;
        return -1;
    }

    /** How many slots hold a reading. */
    public int covered() {
        int c = 0;
        for (int b = 0; b < N; b++) if (hits[b] > 0) c++;
        return c;
    }

    /** The slot where this series is strongest, or -1 when there is no reading. */
    public int worstBin(int series) {
        int best = -1;
        for (int b = 0; b < N; b++) {
            if (hits[b] > 0 && (best < 0 || max[series][b] > max[series][best])) best = b;
        }
        return best;
    }

    /** The values of one series from firstBin to lastBin, for saving. */
    public float[] slice(int series) {
        int a = firstBin(), z = lastBin();
        if (a < 0) return new float[0];
        float[] out = new float[z - a + 1];
        for (int b = a; b <= z; b++) out[b - a] = hits[b] > 0 ? max[series][b] : -1; // -1 = no reading
        return out;
    }

    /** Rebuilds a map from saved slices. */
    public static SweepMap restore(int firstBin, float[][] slices) {
        SweepMap m = new SweepMap();
        if (firstBin < 0 || slices == null || slices.length < SERIES) return m;
        for (int i = 0; i < slices[0].length && firstBin + i < N; i++) {
            if (slices[PEAK][i] < 0) continue;
            m.hits[firstBin + i] = 1;
            for (int s = 0; s < SERIES; s++) m.max[s][firstBin + i] = Math.max(0, slices[s][i]);
        }
        return m;
    }

    /** One line that names the worst spot, e.g. "Strongest at 1,450 rpm: 21.0 mg". */
    public String worstText(int series) {
        int b = worstBin(series);
        if (b < 0 || max[series][b] <= 0) return "";
        return String.format(Locale.US, "Strongest at %,.0f rpm: %.1f mg (%s)", rpmOf(b), max[series][b], NAMES[series]);
    }
}
