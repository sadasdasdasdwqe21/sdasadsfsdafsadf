package app.shakefinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Locale;

/**
 * Shake strength against engine speed, as bars. The strongest slot is
 * highlighted and named; a marker shows where the engine is right now. Bars
 * grow as readings come in. Drag across to read any slot.
 */
public class SweepView extends View {

    private final float dp;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private final float[] shown = new float[SweepMap.N];
    private final int touchSlop;

    private SweepMap map;
    private int series = SweepMap.PEAK;
    private double currentRpm = Double.NaN;
    private boolean live = false;
    private double yMax = 10;
    private long lastFrame = 0;
    private boolean cursorOn = false, dragging = false;
    private float cursorX, downX, downY;
    private long cursorHideAt = 0;

    public SweepView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        bar.setStyle(Paint.Style.FILL);
        grid.setColor(Theme.LINE);
        grid.setStrokeWidth(1 * dp);
        text.setTypeface(Theme.MONO);
        text.setTextSize(10 * dp);
        bubble.setStyle(Paint.Style.FILL);
        setContentDescription("Shake strength against engine speed");
    }

    public void setData(SweepMap map, int series, double currentRpm, boolean live) {
        this.map = map;
        this.series = series;
        this.currentRpm = currentRpm;
        this.live = live;
        if (map != null) {
            int w = map.worstBin(series);
            if (w >= 0 && map.mg(series, w) > 0) {
                setContentDescription(map.worstText(series));
            }
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (int) (170 * dp));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && Math.abs(e.getX() - downX) > touchSlop
                        && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    cursorOn = true;
                    cursorX = e.getX();
                    cursorHideAt = Long.MAX_VALUE;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    cursorOn = true;
                    cursorX = e.getX();
                    performClick();
                }
                dragging = false;
                cursorHideAt = SystemClock.uptimeMillis() + 3500;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                cursorHideAt = SystemClock.uptimeMillis() + 800;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
        lastFrame = now;
        float k = 1f - (float) Math.exp(-dt / 140f);
        boolean moving = false;

        float w = getWidth(), h = getHeight();
        float left = 8 * dp, right = w - 8 * dp, top = 22 * dp, bottom = h - 20 * dp;

        if (map == null || map.isEmpty()) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Theme.MEDIUM);
            text.setTextSize(13 * dp);
            text.setColor(Theme.MUTED);
            c.drawText(live ? "Rev slowly through the range…" : "No readings yet", w / 2, (top + bottom) / 2, text);
            c.drawLine(left, bottom, right, bottom, grid);
            text.setTypeface(Theme.MONO);
            text.setTextSize(10 * dp);
            return;
        }

        // Rpm range on show: whole 500s around the data, at least 1000 wide.
        double lo = Math.floor(SweepMap.rpmOf(map.firstBin()) / 500) * 500;
        double hi = Math.ceil(SweepMap.rpmOf(map.lastBin()) / 500) * 500;
        if (!Double.isNaN(currentRpm)) {
            lo = Math.min(lo, Math.floor(currentRpm / 500) * 500);
            hi = Math.max(hi, Math.ceil(currentRpm / 500) * 500);
        }
        if (hi - lo < 1000) hi = lo + 1000;
        lo = Math.max(lo, 0);

        int worst = map.worstBin(series);
        double peak = worst >= 0 ? map.mg(series, worst) : 0;
        double want = Math.max(peak * 1.2, 4);
        if (Math.abs(want - yMax) > want * 0.01) { yMax += (want - yMax) * k; moving = true; } else yMax = want;

        // Grid and labels.
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Theme.MUTED);
        double step = hi - lo > 3000 ? 1000 : 500;
        for (double r = lo; r <= hi + 1; r += step) {
            float x = (float) (left + (r - lo) / (hi - lo) * (right - left));
            c.drawLine(x, top, x, bottom, grid);
            String t = String.format(Locale.US, "%.0f", r);
            float tw = text.measureText(t);
            c.drawText(t, Math.max(left + tw / 2, Math.min(x, right - tw / 2)), bottom + 14 * dp, text);
        }
        c.drawLine(left, bottom, right, bottom, grid);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(String.format(Locale.US, yMax < 10 ? "%.1f mg" : "%.0f mg", yMax), left + 2 * dp, top - 8 * dp, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("rpm", right, top - 8 * dp, text);

        // Bars.
        float bw = (float) (SweepMap.BIN / (hi - lo) * (right - left));
        int base = series == SweepMap.PEAK ? Theme.SPEED : Theme.ENGINE;
        for (int b = 0; b < SweepMap.N; b++) {
            float target = map.has(b) ? (float) map.mg(series, b) : 0;
            if (Math.abs(target - shown[b]) > yMax * 0.004) { shown[b] += (target - shown[b]) * k; moving = true; } else shown[b] = target;
            double r0 = SweepMap.LO + b * SweepMap.BIN;
            if (r0 < lo || r0 >= hi) continue;
            float x0 = (float) (left + (r0 - lo) / (hi - lo) * (right - left));
            if (map.has(b)) {
                float y = (float) (bottom - Math.min(1.0, shown[b] / yMax) * (bottom - top));
                bar.setColor(b == worst ? Theme.BAD : Theme.alpha(base, 215));
                c.drawRoundRect(x0 + 0.5f * dp, Math.min(y, bottom - 1.5f * dp), x0 + bw - 0.5f * dp, bottom, 2 * dp, 2 * dp, bar);
            }
        }

        // Name the worst slot.
        if (worst >= 0 && peak > 0) {
            float x = (float) (left + (SweepMap.rpmOf(worst) - lo) / (hi - lo) * (right - left));
            float y = (float) (bottom - Math.min(1.0, shown[worst] / yMax) * (bottom - top));
            String t = String.format(Locale.US, "%,.0f", SweepMap.rpmOf(worst));
            text.setTypeface(Theme.MONO_BOLD);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Theme.BAD);
            float tw = text.measureText(t);
            c.drawText(t, Math.max(left + tw / 2, Math.min(x, right - tw / 2)), Math.max(top + 8 * dp, y - 4 * dp), text);
            text.setTypeface(Theme.MONO);
        }

        // Where the engine is now.
        if (!Double.isNaN(currentRpm) && currentRpm >= lo && currentRpm <= hi) {
            float x = (float) (left + (currentRpm - lo) / (hi - lo) * (right - left));
            bar.setColor(Theme.INK);
            c.drawRect(x - 0.75f * dp, top, x + 0.75f * dp, bottom, bar);
            tri.reset();
            tri.moveTo(x - 5 * dp, top - 6 * dp);
            tri.lineTo(x + 5 * dp, top - 6 * dp);
            tri.lineTo(x, top + 1 * dp);
            tri.close();
            c.drawPath(tri, bar);
        }

        // Finger read-out.
        if (cursorOn && now > cursorHideAt) cursorOn = false;
        if (cursorOn) {
            double r = lo + Math.max(0, Math.min(1, (cursorX - left) / (right - left))) * (hi - lo);
            int b = SweepMap.binOf(r);
            if (b >= 0) {
                float x = (float) (left + (SweepMap.rpmOf(b) - lo) / (hi - lo) * (right - left));
                grid.setColor(Theme.alpha(Theme.INK, 170));
                c.drawLine(x, top, x, bottom, grid);
                grid.setColor(Theme.LINE);
                String t = map.has(b)
                        ? String.format(Locale.US, "%,.0f rpm  %.1f mg", SweepMap.rpmOf(b), map.mg(series, b))
                        : String.format(Locale.US, "%,.0f rpm  no reading", SweepMap.rpmOf(b));
                text.setTypeface(Theme.MONO_BOLD);
                text.setTextSize(12 * dp);
                text.setTextAlign(Paint.Align.LEFT);
                float tw = text.measureText(t) + 16 * dp;
                float bx = Math.max(left, Math.min(x - tw / 2, right - tw));
                bubble.setColor(Theme.alpha(Theme.BG, 235));
                c.drawRoundRect(bx, top + 2 * dp, bx + tw, top + 26 * dp, 8 * dp, 8 * dp, bubble);
                text.setColor(Theme.INK);
                c.drawText(t, bx + 8 * dp, top + 18 * dp, text);
                text.setTypeface(Theme.MONO);
                text.setTextSize(10 * dp);
            }
            if (cursorHideAt != Long.MAX_VALUE) moving = true;
        }
        if (moving) postInvalidateOnAnimation();
    }
}
