package app.shakefinder;

import android.graphics.Color;
import android.graphics.Typeface;

/** Colours, type and the shake-strength scale used across the app. */
final class Theme {
    private Theme() {}

    static final int BG = Color.parseColor("#0E1419");
    static final int SURFACE = Color.parseColor("#172028");
    static final int SURFACE_HI = Color.parseColor("#1F2B35");
    static final int INK = Color.parseColor("#E8EEF1");
    static final int MUTED = Color.parseColor("#93A3AE");
    static final int LINE = Color.parseColor("#2B3944");
    static final int ENGINE = Color.parseColor("#FB8A4C");
    static final int PROPSHAFT = Color.parseColor("#3CC7B5");
    static final int TYRE = Color.parseColor("#B79BFF");
    static final int SPEED = Color.parseColor("#5CB8FF");
    static final int OK = Color.parseColor("#7FE0BE");
    static final int WARN = Color.parseColor("#F2C36B");
    static final int BAD = Color.parseColor("#FF6B6B");

    static final Typeface BOLD = Typeface.create("sans-serif-medium", Typeface.BOLD);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface MONO = Typeface.MONOSPACE;
    static final Typeface MONO_BOLD = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    static int familyColor(Orders.Family f) {
        switch (f) {
            case ENGINE: return ENGINE;
            case PROPSHAFT: return PROPSHAFT;
            default: return TYRE;
        }
    }

    static int alpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    static int mix(int a, int b, float t) {
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // Shake strength, as felt with the phone on a seat rail or the console.
    // A rough guide, not a standard.
    static final double[] LEVEL_MG = {5, 15, 40, 100};
    static final String[] LEVEL_NAME = {"Faint", "Mild", "Medium", "Strong", "Severe"};
    static final int[] LEVEL_COLOR = {OK, PROPSHAFT, WARN, ENGINE, BAD};

    static int levelIndex(double mg) {
        int i = 0;
        while (i < LEVEL_MG.length && mg >= LEVEL_MG[i]) i++;
        return i;
    }
}
