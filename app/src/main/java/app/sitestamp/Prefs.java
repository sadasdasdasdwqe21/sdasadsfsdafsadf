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
    static final String POSITION = "position";
    static final String SIZE = "size";
    static final String COLOR = "color";
    static final String BOX = "box";
    static final String PHOTO_SIZE = "photo_size";
    static final String VIDEO_QUALITY = "video_quality";
    static final String AUDIO = "audio";
    static final String SOUND = "shutter_sound";
    static final String GRID = "grid";
    static final String TIMER = "timer";
    static final String FLASH = "flash";
    static final String VIDEO_MODE = "video_mode";

    static final String[] SIZE_NAMES = {"Small", "Medium", "Large", "Extra large"};
    static final float[] SIZE_SCALES = {0.80f, 1.00f, 1.25f, 1.55f};
    static final String[] COLOR_NAMES = {"White", "Yellow", "Orange", "Red", "Green", "Light blue"};
    static final int[] COLORS = {
        0xFFFFFFFF, 0xFFFFE94A, 0xFFFF9A2E, 0xFFFF4D4D, 0xFF5BE38A, 0xFF6FD3FF
    };
    static final String[] POSITION_NAMES = {
        "Bottom left", "Bottom right", "Top left", "Top right"
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
        s.position = clamp(getI(POSITION, 0), 0, 3);
        s.scale = SIZE_SCALES[clamp(getI(SIZE, 1), 0, SIZE_SCALES.length - 1)];
        s.color = COLORS[clamp(getI(COLOR, 0), 0, COLORS.length - 1)];
        s.box = getB(BOX, true);
        return s;
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
