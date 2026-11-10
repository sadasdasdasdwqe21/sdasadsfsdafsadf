package app.sitestamp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and small helpers for building screens in code. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF101214;
    static final int PANEL = 0xFF1B1F23;
    static final int PANEL_HI = 0xFF262B30;
    static final int TEXT = 0xFFF2F2F2;
    static final int DIM = 0xFF9AA3AB;
    static final int ACCENT = StampRenderer.ACCENT;
    static final int GREEN = 0xFF4CD07D;
    static final int RED = 0xFFF0443A;

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
