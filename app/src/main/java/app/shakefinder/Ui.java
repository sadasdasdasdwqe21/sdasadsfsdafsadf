package app.shakefinder;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Small builders so every screen uses the same cards, buttons and type. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Theme.BOLD : android.graphics.Typeface.DEFAULT);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static TextView caption(Context c, String s) {
        TextView t = text(c, 12, Theme.MUTED, true);
        t.setText(s);
        t.setLetterSpacing(0.09f);
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.SURFACE);
        bg.setCornerRadius(dp(c, 14));
        bg.setStroke(dp(c, 1), Theme.LINE);
        l.setBackground(bg);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams gapBelow(Context c, int dpGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, dpGap);
        return lp;
    }

    static LinearLayout.LayoutParams weight(Context c, int heightDp, int leftGapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, heightDp), 1f);
        lp.leftMargin = dp(c, leftGapDp);
        return lp;
    }

    /** A rounded button with a touch ripple. */
    static TextView button(Context c, String label, int fill, int ink) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Theme.MEDIUM);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setMinHeight(dp(c, 48));
        paint(b, fill, ink);
        return b;
    }

    static void paint(TextView b, int fill, int ink) {
        Context c = b.getContext();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(c, 12));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(c, 12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(Theme.INK, 60)), bg, mask));
        b.setTextColor(ink);
    }

    /** A small pill, used for "Tach shows about 786?" and the like. */
    static TextView chip(Context c, String label, int color) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Theme.MEDIUM);
        b.setTextColor(color);
        b.setClickable(true);
        b.setFocusable(true);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setMinHeight(dp(c, 48));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.alpha(color, 30));
        bg.setCornerRadius(dp(c, 24));
        bg.setStroke(dp(c, 1), Theme.alpha(color, 120));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(c, 24));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.alpha(color, 80)), bg, mask));
        return b;
    }

    static EditText field(Context c, int inputType, String hint) {
        EditText e = new EditText(c);
        e.setInputType(inputType);
        e.setTextColor(Theme.INK);
        e.setHintTextColor(Theme.alpha(Theme.MUTED, 150));
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        e.setTypeface(Theme.MONO);
        e.setSingleLine(true);
        e.setBackgroundTintList(ColorStateList.valueOf(Theme.MUTED));
        return e;
    }

    static View labelled(Context c, String label, View input, String help) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(c, 13, Theme.MUTED, false);
        l.setText(label);
        col.addView(l);
        col.addView(input);
        if (help != null) {
            TextView u = text(c, 12, Theme.MUTED, false);
            u.setText(help);
            col.addView(u);
        }
        return col;
    }

    static Switch toggle(Context c, String label, int accent) {
        Switch s = new Switch(c);
        s.setText(label);
        s.setTextColor(Theme.INK);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setMinHeight(dp(c, 48));
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{accent, Theme.MUTED}));
        s.setTrackTintList(new ColorStateList(states, new int[]{Theme.alpha(accent, 110), Theme.LINE}));
        return s;
    }

    static int numberInput() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    }

    /** Fades and slides a view in. Used when the app opens and when a result changes. */
    static void enter(View v, long delayMs) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 14));
        v.animate().alpha(1f).translationY(0).setStartDelay(delayMs).setDuration(260).start();
    }

    static void nudge(View v) {
        v.setAlpha(0.35f);
        v.setTranslationY(dp(v.getContext(), 5));
        v.animate().alpha(1f).translationY(0).setStartDelay(0).setDuration(200).start();
    }
}
