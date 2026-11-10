package app.sitestamp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Builds the lines of text on the stamp. Plain Java, tested on a PC. */
final class StampText {
    private StampText() {}

    static final int ROLE_NORMAL = 0;
    static final int ROLE_COMPANY = 1;
    static final int ROLE_PROJECT = 2;

    static final String[] DATE_PATTERNS = {
        "dd MMM yyyy  HH:mm:ss",
        "dd/MM/yyyy  HH:mm:ss",
        "yyyy-MM-dd  HH:mm:ss",
        "EEE, dd MMM yyyy  HH:mm:ss",
        "dd MMM yyyy  hh:mm:ss a",
        "MM/dd/yyyy  hh:mm:ss a",
    };

    static final class Line {
        final String text;
        final int role;

        Line(String text, int role) {
            this.text = text;
            this.role = role;
        }
    }

    static final class Options {
        boolean showCompany = true;
        boolean showProject = true;
        boolean showPlace = true;
        boolean showDate = true;
        boolean showAddress = true;
        boolean showCoords = true;
        boolean showAltitude = false;
        boolean showAccuracy = false;
        boolean showNote = false;
        /** Used as the top line when the project has no company line of its own. */
        String defaultCompany = "";
        String note = "";
        int datePattern = 0;
        boolean dms = false;
    }

    /** What the GPS knows right now. */
    static final class Fix {
        boolean has;
        double lat, lon;
        boolean hasAlt;
        double alt;
        boolean hasAcc;
        float acc;
        String address = "";
    }

    static List<Line> build(Options o, Project p, Fix f, long timeMs, TimeZone tz) {
        List<Line> out = new ArrayList<>();
        if (o.showCompany) {
            String c = p != null && !blank(p.company) ? p.company : o.defaultCompany;
            if (!blank(c)) out.add(new Line(c.trim(), ROLE_COMPANY));
        }
        if (o.showProject && p != null && !blank(p.name)) {
            out.add(new Line(p.name.trim(), ROLE_PROJECT));
        }
        if (o.showPlace && p != null && !blank(p.place)) {
            out.add(new Line(p.place.trim(), ROLE_NORMAL));
        }
        if (o.showDate) {
            out.add(new Line(date(o.datePattern, timeMs, tz), ROLE_NORMAL));
        }
        if (o.showAddress && f != null && f.has && !blank(f.address)) {
            out.add(new Line(f.address.trim(), ROLE_NORMAL));
        }
        if (o.showCoords) {
            if (f != null && f.has) {
                out.add(new Line(o.dms ? Geo.dms(f.lat, f.lon) : Geo.decimal(f.lat, f.lon),
                        ROLE_NORMAL));
            } else {
                out.add(new Line("Waiting for GPS", ROLE_NORMAL));
            }
        }
        if (f != null && f.has) {
            String extra = "";
            if (o.showAltitude && f.hasAlt) {
                extra = "Alt " + Math.round(f.alt) + " m";
            }
            if (o.showAccuracy && f.hasAcc) {
                extra += (extra.isEmpty() ? "" : "   ") + "GPS ±" + Math.round(f.acc) + " m";
            }
            if (!extra.isEmpty()) out.add(new Line(extra, ROLE_NORMAL));
        }
        if (o.showNote && !blank(o.note)) {
            out.add(new Line(o.note.trim(), ROLE_NORMAL));
        }
        return out;
    }

    static String date(int pattern, long timeMs, TimeZone tz) {
        int i = pattern < 0 || pattern >= DATE_PATTERNS.length ? 0 : pattern;
        SimpleDateFormat f = new SimpleDateFormat(DATE_PATTERNS[i], Locale.US);
        f.setTimeZone(tz);
        return f.format(new Date(timeMs));
    }

    static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
