package app.shakefinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.util.Locale;

/**
 * A round dial with a sweeping arc, a big number in the middle and a status
 * line underneath. The arc glides to each new value, and its tip glows while
 * the reading is live.
 */
public class GaugeView extends View {

    private static final float START_DEG = 150f, SWEEP_DEG = 240f;

    private final float dp;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private String unit = "";
    private String sub = "";
    private int subColor = Theme.MUTED;
    private int accent = Theme.ENGINE;
    private double max = 4000, tickStep = 1000;
    private int decimals = 0;

    private double target = Double.NaN;
    private double shown = 0;
    private float presence = 0; // 0 = no reading, 1 = reading; fades between
    private boolean live = false;
    private long lastFrame = 0;

    public GaugeView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Theme.LINE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.FILL);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(1.5f * dp);
        valueText.setTypeface(Theme.MONO_BOLD);
        valueText.setTextAlign(Paint.Align.CENTER);
        unitText.setTypeface(Theme.MEDIUM);
        unitText.setTextAlign(Paint.Align.CENTER);
        unitText.setColor(Theme.MUTED);
        subText.setTypeface(Theme.MEDIUM);
        subText.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void configure(String unit, double max, double tickStep, int decimals, int accent) {
        this.unit = unit;
        this.max = max;
        this.tickStep = tickStep;
        this.decimals = decimals;
        this.accent = accent;
        invalidate();
    }

    public void setScale(double max, double tickStep) {
        if (this.max == max) return;
        this.max = max;
        this.tickStep = tickStep;
        invalidate();
    }

    public double getScaleMax() {
        return max;
    }

    /** NaN means "no reading". */
    public void setValue(double v) {
        target = v;
        invalidate();
    }

    public void setSub(String text, int color) {
        if (text == null) text = "";
        if (text.equals(sub) && color == subColor) return;
        sub = text;
        subColor = color;
        setContentDescription(unit + " " + text);
        invalidate();
    }

    public void setLive(boolean live) {
        if (this.live == live) return;
        this.live = live;
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) Math.min(w * 0.86f, 190 * dp);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
        lastFrame = now;
        boolean has = !Double.isNaN(target);
        boolean moving = false;

        // Glide toward the target.
        float k = 1f - (float) Math.exp(-dt / 130f);
        if (has) {
            double want = Math.max(0, Math.min(max, target));
            if (presence < 0.05f) shown = want; // first reading: don't sweep up from zero
            double d = want - shown;
            if (Math.abs(d) > max * 0.0005) { shown += d * k; moving = true; } else shown = want;
        }
        float wantPresence = has ? 1f : 0f;
        if (Math.abs(wantPresence - presence) > 0.01f) {
            presence += (wantPresence - presence) * (1f - (float) Math.exp(-dt / 160f));
            moving = true;
        } else presence = wantPresence;

        float w = getWidth(), h = getHeight();
        float stroke = 9 * dp;
        float r = Math.min(w / 2f - stroke, (h - stroke) / 1.62f - 2 * dp);
        float cx = w / 2f, cy = stroke / 2f + r + 3 * dp;
        oval.set(cx - r, cy - r, cx + r, cy + r);

        track.setStrokeWidth(stroke);
        c.drawArc(oval, START_DEG, SWEEP_DEG, false, track);

        // Ticks just inside the arc.
        tick.setColor(Theme.alpha(Theme.MUTED, 120));
        for (double v = 0; v <= max + 1e-6; v += tickStep) {
            double ang = Math.toRadians(START_DEG + SWEEP_DEG * v / max);
            float r1 = r - stroke * 0.5f - 3 * dp, r2 = r1 - 5 * dp;
            c.drawLine(cx + r1 * (float) Math.cos(ang), cy + r1 * (float) Math.sin(ang),
                    cx + r2 * (float) Math.cos(ang), cy + r2 * (float) Math.sin(ang), tick);
        }

        float frac = (float) (shown / max);
        if (presence > 0.01f) {
            arc.setStrokeWidth(stroke);
            arc.setColor(Theme.alpha(accent, Math.round(255 * presence)));
            c.drawArc(oval, START_DEG, Math.max(0.5f, SWEEP_DEG * frac), false, arc);

            // Glowing tip that breathes while live.
            double ang = Math.toRadians(START_DEG + SWEEP_DEG * frac);
            float tx = cx + r * (float) Math.cos(ang), ty = cy + r * (float) Math.sin(ang);
            float breathe = live ? 0.5f + 0.5f * (float) Math.sin(now / 420.0) : 0.3f;
            glow.setColor(Theme.alpha(accent, Math.round(40 * presence * (0.6f + breathe))));
            c.drawCircle(tx, ty, stroke * (1.25f + 0.55f * breathe), glow);
            glow.setColor(Theme.alpha(Theme.INK, Math.round(235 * presence)));
            c.drawCircle(tx, ty, stroke * 0.32f, glow);
        }

        // Number.
        valueText.setTextSize(Math.min(34 * dp, r * 0.52f));
        String text;
        if (presence > 0.5f) {
            text = decimals == 0 ? String.format(Locale.US, "%.0f", shown)
                    : String.format(Locale.US, "%." + decimals + "f", shown);
            valueText.setColor(Theme.INK);
        } else {
            text = "– –";
            valueText.setColor(Theme.MUTED);
        }
        float vy = cy + valueText.getTextSize() * 0.30f;
        c.drawText(text, cx, vy, valueText);
        unitText.setTextSize(11 * dp);
        unitText.setLetterSpacing(0.12f);
        c.drawText(unit, cx, vy + 16 * dp, unitText);

        // Status line in the gap of the arc.
        subText.setTextSize(11.5f * dp);
        subText.setColor(subColor);
        String s = sub;
        float maxW = w - 8 * dp;
        if (subText.measureText(s) > maxW) {
            while (s.length() > 4 && subText.measureText(s + "…") > maxW) s = s.substring(0, s.length() - 1);
            s = s + "…";
        }
        c.drawText(s, cx, h - 5 * dp, subText);

        if (moving || live) postInvalidateOnAnimation();
    }
}
