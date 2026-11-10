package app.sitestamp;

import android.content.Context;
import android.content.SharedPreferences;

/** The saved settings. */
final class Prefs {
    static final String SHOW_COMPANY = "show_company";
    static final String SHOW_PROJECT = "show_project";
    static final String SHOW_PLACE = "show_place";
    static final String SHOW_DATE = "show_date";
    static final String SHOW_ADDRESS = "show_address";
    static final String SHOW_COORDS = "show_coords";
    static final String SHOW_ALTITUDE = "show_altitude";
    static final String SHOW_ACCURACY = "show_accuracy";
    static final String SHOW_NOTE = "show_note";
    static final String COMPANY = "company";
    static final String NOTE = "note";
    static final String DATE_PATTERN = "date_pattern";
    static final String DMS = "dms";
    /** 0 to 8, counted along the rows from top left to bottom right. */
    static final String PLACE9 = "place9";
    /** Text size in percent of the normal size. */
    static final String SIZE_PCT = "size_pct";
    /** 0 to 20: how far the stamp sits from the picture's edge. */
    static final String EDGE = "edge";
    static final String COLOR = "color";
    static final String BOX_STYLE = "box_style";
    static final String THEME = "theme";
    static final String PHOTO_SIZE = "photo_size";
    static final String VIDEO_QUALITY = "video_quality";
    static final String AUDIO = "audio";
    static final String SOUND = "shutter_sound";
    static final String GRID = "grid";
    static final String TIMER = "timer";
    static final String FLASH = "flash";
    static final String VIDEO_MODE = "video_mode";

    // Settings from version 1.0, read once to carry the old choice over.
    private static final String OLD_POSITION = "position";
    private static final String OLD_SIZE = "size";
    private static final String OLD_BOX = "box";

    static final int SIZE_MIN = 40;
    static final int SIZE_MAX = 300;
    static final int SIZE_STEP = 5;
    static final int EDGE_MAX = 20;
    static final int EDGE_DEFAULT = 5;

    static final String[] COLOR_NAMES = {
        "White", "Yellow", "Orange", "Red", "Green", "Light blue", "Black"
    };
    static final int[] COLORS = {
        0xFFFFFFFF, 0xFFFFE94A, 0xFFFF9A2E, 0xFFFF4D4D, 0xFF5BE38A, 0xFF6FD3FF, 0xFF111111
    };
    static final int COLOR_WHITE = 0;
    static final int COLOR_BLACK = 6;
    static final String[] PLACE_NAMES = {
        "Top left", "Top middle", "Top right",
        "Middle left", "Centre", "Middle right",
        "Bottom left", "Bottom middle", "Bottom right"
    };
    static final String[] BOX_NAMES = {
        "No box", "Dark box", "Light box"
    };
    static final String[] THEME_NAMES = {
        "Same as the phone", "Dark", "Light"
    };
    static final String[] PHOTO_SIZE_NAMES = {
        "Full (best detail)", "Medium (about 8 MP)", "Small (about 3 MP, easy to send)"
    };
    static final String[] VIDEO_QUALITY_NAMES = {"Full HD 1080p", "HD 720p", "SD 480p"};

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("sitestamp", Context.MODE_PRIVATE);
    }

    boolean getB(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    void setB(String key, boolean v) {
        sp.edit().putBoolean(key, v).apply();
    }

    int getI(String key, int def) {
        return sp.getInt(key, def);
    }

    void setI(String key, int v) {
        sp.edit().putInt(key, v).apply();
    }

    String getS(String key, String def) {
        return sp.getString(key, def);
    }

    void setS(String key, String v) {
        sp.edit().putString(key, v).apply();
    }

    static boolean defaultFor(String key) {
        return !(SHOW_ALTITUDE.equals(key) || SHOW_ACCURACY.equals(key) || SHOW_NOTE.equals(key));
    }

    /** Where the stamp sits, 0 to 8. */
    int place() {
        if (sp.contains(PLACE9)) return clamp(getI(PLACE9, 6), 0, 8);
        // Version 1.0 had four corners: bottom left, bottom right, top left, top right.
        int[] fromOld = {6, 8, 0, 2};
        return fromOld[clamp(getI(OLD_POSITION, 0), 0, 3)];
    }

    /** Text size in percent. */
    int sizePct() {
        if (sp.contains(SIZE_PCT)) return clamp(getI(SIZE_PCT, 100), SIZE_MIN, SIZE_MAX);
        int[] fromOld = {80, 100, 125, 155};
        return fromOld[clamp(getI(OLD_SIZE, 1), 0, 3)];
    }

    int boxStyle() {
        if (sp.contains(BOX_STYLE)) return clamp(getI(BOX_STYLE, 1), 0, 2);
        return getB(OLD_BOX, true) ? StampRenderer.BOX_DARK : StampRenderer.BOX_NONE;
    }

    int edge() {
        return clamp(getI(EDGE, EDGE_DEFAULT), 0, EDGE_MAX);
    }

    StampText.Options options() {
        StampText.Options o = new StampText.Options();
        o.showCompany = getB(SHOW_COMPANY, true);
        o.showProject = getB(SHOW_PROJECT, true);
        o.showPlace = getB(SHOW_PLACE, true);
        o.showDate = getB(SHOW_DATE, true);
        o.showAddress = getB(SHOW_ADDRESS, true);
        o.showCoords = getB(SHOW_COORDS, true);
        o.showAltitude = getB(SHOW_ALTITUDE, false);
        o.showAccuracy = getB(SHOW_ACCURACY, false);
        o.showNote = getB(SHOW_NOTE, false);
        o.defaultCompany = getS(COMPANY, "");
        o.note = getS(NOTE, "");
        o.datePattern = getI(DATE_PATTERN, 0);
        o.dms = getB(DMS, false);
        return o;
    }

    StampRenderer.Style style() {
        StampRenderer.Style s = new StampRenderer.Style();
        int place = place();
        s.h = place % 3;
        s.v = place / 3;
        s.scale = sizePct() / 100f;
        s.color = COLORS[clamp(getI(COLOR, 0), 0, COLORS.length - 1)];
        s.box = boxStyle();
        s.margin = 0.010f + 0.005f * edge();
        return s;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
