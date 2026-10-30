package app.shakefinder;

import android.graphics.Color;

/** Colours used across the app. */
final class Theme {
    private Theme() {}

    static final int BG = Color.parseColor("#11181E");
    static final int SURFACE = Color.parseColor("#1A242C");
    static final int INK = Color.parseColor("#E6ECEF");
    static final int MUTED = Color.parseColor("#9AA8B2");
    static final int LINE = Color.parseColor("#2E3B45");
    static final int ENGINE = Color.parseColor("#FB8A4C");
    static final int PROPSHAFT = Color.parseColor("#3CC7B5");
    static final int TYRE = Color.parseColor("#B79BFF");
    static final int OK = Color.parseColor("#8EE3C9");
    static final int WARN = Color.parseColor("#F2C38F");

    static int familyColor(Orders.Family f) {
        switch (f) {
            case ENGINE: return ENGINE;
            case PROPSHAFT: return PROPSHAFT;
            default: return TYRE;
        }
    }
}
