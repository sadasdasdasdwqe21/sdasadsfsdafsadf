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

        Source(String shortName, String name, Family family, double hz, String what, String check) {
            this.shortName = shortName;
            this.name = name;
            this.family = family;
            this.hz = hz;
            this.what = what;
            this.check = check;
        }

        /** Relative difference of a measured frequency from this source, e.g. 0.02 = +2%. */
        public double diff(double measuredHz) {
            return (measuredHz - hz) / hz;
        }
    }

    private static final Pattern TYRE = Pattern.compile("^(\\d{3})/(\\d{2})[A-Z]*?(\\d{2})$");

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
        return Math.PI * diaMm / 1000.0;
    }

    public static List<Source> compute(double rpm, double kmh, String tyreSize, double finalDrive) {
        List<Source> list = new ArrayList<>();
        if (rpm > 0) {
            double crank = rpm / 60.0;
            list.add(new Source("½E", "Engine ½ order", Family.ENGINE, crank * 0.5,
                    "One cylinder is working weaker than the rest. Its power stroke comes up short every second crank turn.",
                    "Roughness monitor under load, swap the coil from the rough cylinder, injector leak-down test, compression test."));
            list.add(new Source("1E", "Engine 1st order", Family.ENGINE, crank,
                    "Something spinning at crank speed is out of balance.",
                    "Crank pulley damper rubber, drive plate and its bolts, torque converter, accessory pulleys."));
            list.add(new Source("2E", "Engine 2nd order", Family.ENGINE, crank * 2,
                    "Normal 4-cylinder firing pulses are getting into the body instead of being absorbed.",
                    "Torque-converter lock-up at low rpm (lugging), mount quality, exhaust hangers and brackets, heat shields, intake pipe brackets."));
            list.add(new Source("4E", "Engine 4th order", Family.ENGINE, crank * 4,
                    "A harmonic of the firing pulses. Usually heard as a boom or drone more than felt.",
                    "Exhaust contact or missing hangers, loose heat shields, body panels resonating at that rpm."));
        }
        double circ = tyreCircumferenceM(tyreSize);
        if (kmh > 0 && !Double.isNaN(circ)) {
            double tyreHz = (kmh / 3.6) / circ;
            list.add(new Source("1T", "Tyre 1st order", Family.TYRE, tyreHz,
                    "A wheel or tyre is out of balance, or a rim is bent.",
                    "Balance all four wheels, check rim runout and hub seating."));
            list.add(new Source("2T", "Tyre 2nd order", Family.TYRE, tyreHz * 2,
                    "A tyre is out of round or has a shifted belt.",
                    "Road-force balance, look for flat spots or bulges."));
            if (finalDrive > 0) {
                list.add(new Source("1P", "Propshaft 1st order", Family.PROPSHAFT, tyreHz * finalDrive,
                        "The propshaft is out of balance or its centre bearing is worn.",
                        "Centre bearing rubber, propshaft balance weights, flange bolts."));
                list.add(new Source("2P", "Propshaft 2nd order", Family.PROPSHAFT, tyreHz * finalDrive * 2,
                        "A universal joint is working at an angle or is worn.",
                        "Propshaft U-joints, transmission mount height and angle."));
            }
        }
        return list;
    }

    /** Closest source within the tolerance (e.g. 0.06 = ±6%), or null. */
    public static Source bestMatch(List<Source> list, double measuredHz, double tolerance) {
        Source best = null;
        double bestAbs = Double.MAX_VALUE;
        for (Source s : list) {
            if (s.hz <= 0) continue;
            double d = Math.abs(s.diff(measuredHz));
            if (d <= tolerance && d < bestAbs) {
                best = s;
                bestAbs = d;
            }
        }
        return best;
    }

    /** Closest source regardless of tolerance, or null if the list is empty. */
    public static Source nearest(List<Source> list, double measuredHz) {
        return bestMatch(list, measuredHz, Double.MAX_VALUE);
    }
}
