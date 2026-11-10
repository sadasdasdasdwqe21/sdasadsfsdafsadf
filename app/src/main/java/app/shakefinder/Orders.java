package app.shakefinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Where each rotating part of the car shakes, and what a match means. Plain Java. */
public final class Orders {

    private Orders() {}

    public enum Family { ENGINE, PROPSHAFT, TYRE }

    public static final class Source {
        public final String shortName;
        public final String name;
        public final Family family;
        public final double hz;
        public final String what;
        public final String check;
        /** Main orders get a line on the chart; extra half and higher orders only label peaks. */
        public final boolean drawLine;
        /** How far a measured peak may sit from this source and still count, e.g. 0.03 = ±3%. */
        public final double tol;

        Source(String shortName, String name, Family family, double hz, String what, String check, double tol) {
            this(shortName, name, family, hz, what, check, tol, true);
        }

        Source(String shortName, String name, Family family, double hz, String what, String check, double tol, boolean drawLine) {
            this.shortName = shortName;
            this.name = name;
            this.family = family;
            this.hz = hz;
            this.what = what;
            this.check = check;
            this.tol = tol;
            this.drawLine = drawLine;
        }

        /** Relative difference of a measured frequency from this source, e.g. 0.02 = +2%. */
        public double diff(double measuredHz) {
            return (measuredHz - hz) / hz;
        }
    }

    private static final Pattern TYRE = Pattern.compile("^(\\d{3})/(\\d{2})[A-Z]*?(\\d{2})$");

    /**
     * A loaded tyre is squashed at the bottom, so it travels about 3% less per
     * turn than its unloaded size suggests. Tyre makers' "revs per km" figures
     * follow the same rule.
     */
    public static final double ROLLING_FACTOR = 0.97;

    /** Rolling circumference in metres from a size like 225/55R18, or NaN if it can't be read. */
    public static double tyreCircumferenceM(String size) {
        if (size == null) return Double.NaN;
        String s = size.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        Matcher m = TYRE.matcher(s);
        if (!m.matches()) return Double.NaN;
        double width = Double.parseDouble(m.group(1));
        double aspect = Double.parseDouble(m.group(2));
        double rim = Double.parseDouble(m.group(3));
        double diaMm = rim * 25.4 + 2 * width * aspect / 100.0;
        return Math.PI * diaMm / 1000.0 * ROLLING_FACTOR;
    }

    /**
     * @param engTol  match width for engine orders: tight when the rpm was measured, wide when it was typed
     * @param roadTol match width for tyre and propshaft orders: tight with GPS speed, wide with a typed speed
     */
    public static List<Source> compute(double rpm, double kmh, String tyreSize, double finalDrive,
                                       double engTol, double roadTol) {
        List<Source> list = new ArrayList<>();
        if (rpm > 0) {
            double crank = rpm / 60.0;
            list.add(new Source("½E", "Engine ½ order", Family.ENGINE, crank * 0.5,
                    "One cylinder is working weaker than the rest. Its power stroke comes up short every second crank turn.",
                    "Roughness monitor under load, swap the coil from the rough cylinder, injector leak-down test, compression test.", engTol));
            list.add(new Source("1E", "Engine 1st order", Family.ENGINE, crank,
                    "Something spinning at crank speed is out of balance.",
                    "Crank pulley damper rubber, drive plate and its bolts, torque converter, accessory pulleys.", engTol));
            list.add(new Source("2E", "Engine 2nd order", Family.ENGINE, crank * 2,
                    "Normal 4-cylinder firing pulses are getting into the body instead of being absorbed.",
                    "Torque-converter lock-up at low rpm (lugging), mount quality, exhaust hangers and brackets, heat shields, intake pipe brackets.", engTol));
            list.add(new Source("4E", "Engine 4th order", Family.ENGINE, crank * 4,
                    "A harmonic of the firing pulses. Strong only when a part is resonating at this frequency.",
                    "Tap-test the exhaust, intake pipe, heat shields and steering column. Also check for missing exhaust hangers or brackets.", engTol));
            for (double k : new double[]{1.5, 2.5, 3.5, 4.5, 5.5}) {
                list.add(new Source(orderLabel(k) + "E", "Engine " + orderLabel(k) + " order", Family.ENGINE, crank * k,
                        "A half order. Cylinders aren't firing evenly, so the pattern only repeats every two crank turns.",
                        "Roughness monitor under load, swap coils between cylinders, injector leak-down test, compression test.",
                        Math.min(engTol, 0.03), false));
            }
            for (double k : new double[]{3, 5, 6, 7, 8, 9, 10, 11, 12}) {
                list.add(new Source(orderLabel(k) + "E", "Engine " + orderLabel(k) + " order", Family.ENGINE, crank * k,
                        "A harmonic of crank rotation. It is small on its own, so if it is the strongest peak a part is probably resonating at this frequency.",
                        "Tap-test the exhaust, intake pipe, heat shields and steering column to find the part that rings at this frequency.",
                        Math.min(engTol, 0.03), false));
            }
        }
        double circ = tyreCircumferenceM(tyreSize);
        if (kmh > 0 && !Double.isNaN(circ)) {
            double tyreHz = (kmh / 3.6) / circ;
            list.add(new Source("1T", "Tyre 1st order", Family.TYRE, tyreHz,
                    "A wheel or tyre is out of balance, or a rim is bent.",
                    "Balance all four wheels, check rim runout and hub seating.", roadTol));
            list.add(new Source("2T", "Tyre 2nd order", Family.TYRE, tyreHz * 2,
                    "A tyre is out of round or has a shifted belt.",
                    "Road-force balance, look for flat spots or bulges.", roadTol));
            if (finalDrive > 0) {
                list.add(new Source("1P", "Propshaft 1st order", Family.PROPSHAFT, tyreHz * finalDrive,
                        "The propshaft is out of balance or its centre bearing is worn.",
                        "Centre bearing rubber, propshaft balance weights, flange bolts.", roadTol));
                list.add(new Source("2P", "Propshaft 2nd order", Family.PROPSHAFT, tyreHz * finalDrive * 2,
                        "A universal joint is working at an angle or is worn.",
                        "Propshaft U-joints, transmission mount height and angle.", roadTol));
            }
        }
        return list;
    }

    /**
     * Closest source whose tolerance covers the measured frequency, or null.
     *
     * @param df spectrum resolution in Hz; a peak can't be placed finer than about one bin
     */
    public static Source bestMatch(List<Source> list, double measuredHz, double df) {
        Source best = null;
        double bestAbs = Double.MAX_VALUE;
        for (Source s : list) {
            if (s.hz <= 0) continue;
            double d = Math.abs(s.diff(measuredHz));
            if (d <= Math.max(s.tol, 1.2 * df / s.hz) && d < bestAbs) {
                best = s;
                bestAbs = d;
            }
        }
        return best;
    }

    /**
     * A second source from a different part of the car that also fits this
     * frequency, or null. When there is one, the test can't tell the two apart yet.
     */
    public static Source rival(List<Source> list, Source winner, double measuredHz, double df) {
        if (winner == null) return null;
        Source best = null;
        double bestAbs = Double.MAX_VALUE;
        for (Source s : list) {
            if (s.hz <= 0 || s.family == winner.family) continue;
            if (!s.drawLine) continue; // only the main orders count as a rival
            double d = Math.abs(s.diff(measuredHz));
            if (d <= Math.max(s.tol, 1.2 * df / s.hz) && d < bestAbs) {
                best = s;
                bestAbs = d;
            }
        }
        return best;
    }

    /** "½", "1½", "1st", "2nd", "3rd", "4th"… */
    public static String orderLabel(double k) {
        if (k == 0.5) return "½";
        if (k != Math.floor(k)) return (int) Math.floor(k) + "½";
        int n = (int) k;
        String suf = (n % 100 >= 11 && n % 100 <= 13) ? "th"
                : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
        return n + suf;
    }

    /** Closest source regardless of tolerance, or null if the list is empty. */
    public static Source nearest(List<Source> list, double measuredHz) {
        Source best = null;
        double bestAbs = Double.MAX_VALUE;
        for (Source s : list) {
            if (s.hz <= 0) continue;
            double d = Math.abs(s.diff(measuredHz));
            if (d < bestAbs) {
                best = s;
                bestAbs = d;
            }
        }
        return best;
    }

    /** Frequencies owned by the tyres and propshaft, for keeping them out of the rpm reading. */
    public static double[] roadFrequencies(double kmh, String tyreSize, double finalDrive) {
        double circ = tyreCircumferenceM(tyreSize);
        if (!(kmh > 0) || Double.isNaN(circ)) return new double[0];
        double tyreHz = (kmh / 3.6) / circ;
        if (finalDrive > 0) return new double[]{tyreHz, tyreHz * 2, tyreHz * finalDrive, tyreHz * finalDrive * 2};
        return new double[]{tyreHz, tyreHz * 2};
    }
}
