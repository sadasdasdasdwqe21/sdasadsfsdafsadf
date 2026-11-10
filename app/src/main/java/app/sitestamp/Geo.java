package app.sitestamp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Distance, coordinate parsing and coordinate text. Plain Java, tested on a PC. */
final class Geo {
    private Geo() {}

    private static final double EARTH_M = 6371008.8;
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");
    private static final Pattern HEMI = Pattern.compile("(?<![A-Z])[NSEW](?![A-Z])");

    /** Great-circle distance in metres. */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * Reads a typed or pasted position. Accepts "-2.5164, 32.9175", "2.5164 S 32.9175 E"
     * and degrees-minutes-seconds such as 2°30'59.0"S 32°55'03.0"E.
     * Returns {lat, lon}, or null when the text is not a position.
     */
    static double[] parseLatLon(String text) {
        if (text == null) return null;
        String t = text.trim().toUpperCase(Locale.US);
        if (t.isEmpty()) return null;
        t = t.replace("LATITUDE", " ").replace("LONGITUDE", " ")
                .replace("NORTH", " N ").replace("SOUTH", " S ")
                .replace("EAST", " E ").replace("WEST", " W ")
                .replace("LAT", " ").replace("LONG", " ").replace("LON", " ").replace("LNG", " ");

        List<Double> nums = new ArrayList<>();
        Matcher m = NUMBER.matcher(t);
        while (m.find()) {
            try {
                nums.add(Double.parseDouble(m.group()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        List<Character> hemis = new ArrayList<>();
        Matcher h = HEMI.matcher(t);
        while (h.find()) hemis.add(h.group().charAt(0));

        int n = nums.size();
        if (n != 2 && n != 4 && n != 6) return null;
        int per = n / 2;
        double a = compose(nums, 0, per);
        double b = compose(nums, per, per);

        if (hemis.size() == 2) {
            char h1 = hemis.get(0), h2 = hemis.get(1);
            a = signed(Math.abs(a), h1);
            b = signed(Math.abs(b), h2);
            boolean firstIsLon = h1 == 'E' || h1 == 'W';
            boolean secondIsLon = h2 == 'E' || h2 == 'W';
            if (firstIsLon == secondIsLon) return null;
            if (firstIsLon) {
                double s = a;
                a = b;
                b = s;
            }
        } else if (!hemis.isEmpty()) {
            return null;
        }
        if (Double.isNaN(a) || Double.isNaN(b)) return null;
        if (a < -90 || a > 90 || b < -180 || b > 180) return null;
        return new double[] {a, b};
    }

    private static double compose(List<Double> nums, int from, int count) {
        double deg = nums.get(from);
        boolean neg = deg < 0 || (deg == 0 && 1 / deg < 0);
        double v = Math.abs(deg);
        if (count >= 2) {
            double min = nums.get(from + 1);
            if (min < 0 || min >= 60) return Double.NaN;
            v += min / 60.0;
        }
        if (count >= 3) {
            double sec = nums.get(from + 2);
            if (sec < 0 || sec >= 60) return Double.NaN;
            v += sec / 3600.0;
        }
        return neg ? -v : v;
    }

    private static double signed(double v, char hemi) {
        return (hemi == 'S' || hemi == 'W') ? -v : v;
    }

    /** "2.51640°S  32.91750°E" */
    static String decimal(double lat, double lon) {
        return String.format(Locale.US, "%.5f°%s  %.5f°%s",
                Math.abs(lat), lat < 0 ? "S" : "N", Math.abs(lon), lon < 0 ? "W" : "E");
    }

    /** "-2.516400, 32.917500", the form map apps accept. */
    static String plain(double lat, double lon) {
        return String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    /** 2°30'59.0"S  32°55'03.0"E */
    static String dms(double lat, double lon) {
        return dmsPart(lat, lat < 0 ? "S" : "N") + "  " + dmsPart(lon, lon < 0 ? "W" : "E");
    }

    private static String dmsPart(double value, String hemi) {
        double v = Math.abs(value);
        long tenths = Math.round(v * 36000.0);
        long deg = tenths / 36000;
        long min = (tenths % 36000) / 600;
        double sec = (tenths % 600) / 10.0;
        return String.format(Locale.US, "%d°%02d'%04.1f\"%s", deg, min, sec, hemi);
    }

    /** "35 m", "2.3 km", "48 km" */
    static String distanceLabel(double metres) {
        if (metres < 950) return Math.round(metres) + " m";
        if (metres < 9950) return String.format(Locale.US, "%.1f km", metres / 1000.0);
        return Math.round(metres / 1000.0) + " km";
    }
}
