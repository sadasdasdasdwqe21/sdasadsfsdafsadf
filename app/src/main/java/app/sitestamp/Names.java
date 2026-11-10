package app.sitestamp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Folder and file names. Plain Java, tested on a PC. */
final class Names {
    private Names() {}

    static final String NO_PROJECT_FOLDER = "Other";

    /** Makes a project name safe to use as a folder or file name. */
    static String safe(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean lastSpace = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean bad = c < 32 || "\\/:*?\"<>|".indexOf(c) >= 0;
            if (bad || Character.isWhitespace(c)) {
                if (!lastSpace) sb.append(' ');
                lastSpace = true;
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        String s = sb.toString().trim();
        while (s.startsWith(".")) s = s.substring(1).trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.length() > 40) s = s.substring(0, 40).trim();
        return s;
    }

    static String folder(Project p) {
        String s = p == null ? "" : safe(p.name);
        return s.isEmpty() ? NO_PROJECT_FOLDER : s;
    }

    /** "Katoro NCU_20261110_150322.jpg" */
    static String file(Project p, long timeMs, TimeZone tz, String extension) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        f.setTimeZone(tz);
        String s = p == null ? "" : safe(p.name);
        if (s.isEmpty()) s = "Photo";
        return s + "_" + f.format(new Date(timeMs)) + "." + extension;
    }
}
