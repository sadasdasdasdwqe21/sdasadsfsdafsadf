package app.shakefinder;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Locale;

/**
 * Records a test run. Four times a second it adds one line to a CSV file
 * (engine speed, road speed, the strongest peak, the strength of each engine
 * order, sound level and every OBD value), and feeds the shake-against-rpm map.
 *
 * Plain Java, no Android imports.
 */
public final class Recorder {

    public final SweepMap sweep = new SweepMap();
    private Writer out;
    private File file;
    private double t0 = Double.NaN, tLast = 0;
    private int rows = 0, obdColumns = 0;
    private double rpmMin = Double.NaN, rpmMax = Double.NaN;
    private double maxMg = 0, maxHz = Double.NaN, maxRpm = Double.NaN;
    private String maxTag = "";

    public boolean isRecording() {
        return out != null;
    }

    public File file() { return file; }
    public int rows() { return rows; }
    public double seconds() { return tLast; }
    public double rpmMin() { return rpmMin; }
    public double rpmMax() { return rpmMax; }
    public double maxMg() { return maxMg; }
    public double maxHz() { return maxHz; }
    public double maxRpm() { return maxRpm; }
    public String maxTag() { return maxTag; }

    /**
     * @param obdNames one column name per OBD value that will be passed to {@link #row}, e.g. "coolant_C"
     */
    public void start(File csv, String[] obdNames) throws IOException {
        stop();
        sweep.clear();
        file = csv;
        rows = 0;
        t0 = Double.NaN;
        tLast = 0;
        rpmMin = rpmMax = Double.NaN;
        maxMg = 0;
        maxHz = maxRpm = Double.NaN;
        maxTag = "";
        obdColumns = obdNames == null ? 0 : obdNames.length;
        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csv), "UTF-8"));
        StringBuilder h = new StringBuilder("time_s,rpm,rpm_source,speed_kmh,speed_source,peak_hz,peak_mg,peak_match,"
                + "half_order_mg,first_order_mg,second_order_mg,fourth_order_mg,tyre_1st_mg,propshaft_1st_mg,sound_dbfs");
        for (int i = 0; i < obdColumns; i++) h.append(',').append(clean(obdNames[i]));
        h.append('\n');
        out.write(h.toString());
    }

    /**
     * Adds one line. NaN leaves a cell empty.
     *
     * @param mapOk true when the rpm reading is good enough to go on the shake-against-rpm map
     */
    public void row(double tSec, double rpm, String rpmSource, double kmh, String speedSource,
                    double peakHz, double peakMg, String peakMatch, double[] orderMg,
                    double tyreMg, double propMg, double soundDb, double[] obd, boolean mapOk) throws IOException {
        if (out == null) return;
        if (Double.isNaN(t0)) t0 = tSec;
        tLast = tSec - t0;
        StringBuilder sb = new StringBuilder(160);
        sb.append(String.format(Locale.US, "%.2f", tLast)).append(',');
        cell(sb, rpm, 0).append(',').append(clean(rpmSource)).append(',');
        cell(sb, kmh, 1).append(',').append(clean(speedSource)).append(',');
        cell(sb, peakHz, 2).append(',');
        cell(sb, peakMg, 2).append(',').append(clean(peakMatch)).append(',');
        for (int i = 0; i < 4; i++) cell(sb, orderMg == null ? Double.NaN : orderMg[i], 2).append(',');
        cell(sb, tyreMg, 2).append(',');
        cell(sb, propMg, 2).append(',');
        cell(sb, soundDb, 1);
        for (int i = 0; i < obdColumns; i++) {
            sb.append(',');
            cell(sb, obd != null && i < obd.length ? obd[i] : Double.NaN, 2);
        }
        sb.append('\n');
        out.write(sb.toString());
        rows++;
        if (rows % 20 == 0) out.flush();

        if (!Double.isNaN(rpm) && rpm > 0) {
            rpmMin = Double.isNaN(rpmMin) ? rpm : Math.min(rpmMin, rpm);
            rpmMax = Double.isNaN(rpmMax) ? rpm : Math.max(rpmMax, rpm);
        }
        if (!Double.isNaN(peakMg) && peakMg > maxMg) {
            maxMg = peakMg;
            maxHz = peakHz;
            maxRpm = rpm;
            maxTag = peakMatch == null ? "" : peakMatch;
        }
        if (mapOk && !Double.isNaN(rpm) && orderMg != null) {
            sweep.add(rpm, orderMg, Double.isNaN(peakMg) ? 0 : peakMg);
        }
    }

    public void stop() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException ignored) {
            // the file keeps whatever reached the disk
        }
        out = null;
    }

    private static StringBuilder cell(StringBuilder sb, double v, int decimals) {
        if (!Double.isNaN(v) && !Double.isInfinite(v)) sb.append(String.format(Locale.US, "%." + decimals + "f", v));
        return sb;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace(',', ' ').replace('\n', ' ').replace('"', ' ').trim();
    }
}
