package app.sitestamp;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and small helpers for building screens in code. */
final class Ui {
    private Ui() {}

    static final int THEME_PHONE = 0;
    static final int THEME_DARK = 1;
    static final int THEME_LIGHT = 2;

    // The app's colours. Set by applyTheme() before a screen is built.
    static boolean light;
    static int BG = 0xFF101214;
    static int PANEL = 0xFF1B1F23;
    static int PANEL_HI = 0xFF262B30;
    static int TEXT = 0xFFF2F2F2;
    static int DIM = 0xFF9AA3AB;
    /** Amber for text and icons. Darker in the light theme so it stays readable. */
    static int ACCENT_TEXT = 0xFFFFB300;
    static int GREEN = 0xFF4CD07D;

    /** Amber for filled buttons. The same in both themes. */
    static final int ACCENT = StampRenderer.ACCENT;
    static final int RED = 0xFFF0443A;

    // For labels that float on the camera picture. The same in both themes.
    static final int PIC_TEXT = 0xFFF2F2F2;
    static final int PIC_DIM = 0xFFC2C9CF;
    static final int PIC_GREEN = 0xFF4CD07D;

    /**
     * Picks dark or light from the setting. Call first thing in onCreate, before
     * super.onCreate, so windows and pop-ups get the matching look.
     *
     * @return the theme setting that was applied, to notice later when it changes
     */
    static int applyTheme(Activity a) {
        int choice = new Prefs(a).getI(Prefs.THEME, THEME_DARK);
        boolean night = (a.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        light = choice == THEME_LIGHT || (choice == THEME_PHONE && !night);
        if (light) {
            BG = 0xFFF3F4F6;
            PANEL = 0xFFFFFFFF;
            PANEL_HI = 0xFFE3E6EA;
            TEXT = 0xFF16191C;
            DIM = 0xFF5F6871;
            ACCENT_TEXT = 0xFFA85F00;
            GREEN = 0xFF1B8A47;
        } else {
            BG = 0xFF101214;
            PANEL = 0xFF1B1F23;
            PANEL_HI = 0xFF262B30;
            TEXT = 0xFFF2F2F2;
            DIM = 0xFF9AA3AB;
            ACCENT_TEXT = 0xFFFFB300;
            GREEN = 0xFF4CD07D;
        }
        a.setTheme(light ? android.R.style.Theme_DeviceDefault_Light_NoActionBar
                : android.R.style.Theme_DeviceDefault_NoActionBar);
        return choice;
    }

    /** Colours the bars at the top and bottom of the screen to match the theme. */
    @SuppressWarnings("deprecation")
    static void applyBars(Activity a) {
        Window w = a.getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        w.getDecorView().setSystemUiVisibility(light
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                : 0);
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 15, primary ? 0xFF1A1300 : TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(primary ? ACCENT : PANEL_HI, dp(c, 12)));
        int p = dp(c, 14);
        b.setPadding(p, p, p, p);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, int l, int t, int r,
            int b) {
        lp.setMargins(l, t, r, b);
        return lp;
    }

    static View space(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }
}
