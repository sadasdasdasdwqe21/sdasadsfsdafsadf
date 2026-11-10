package app.shakefinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The live spectrum: vibration in mg or engine sound in dB, with the expected
 * order lines on top.
 *
 * The curve, the scale and the order lines glide between readings instead of
 * jumping. Drag a finger across the chart to read any frequency; tap a peak to
 * select it.
 */
public class SpectrumView extends View {

    public interface Listener {
        /** The user tapped at or near this frequency. */
        void onPickFrequency(double hz);
    }

    /** A dot on a measured peak. */
    public static final class Mark {
        public final double hz, y;
        public final int color;

        public Mark(double hz, double y, int color) {
            this.hz = hz;
            this.y = y;
            this.color = color;
        }
    }

    // Data (targets).
    private double df = 0;
    private double[] values;           // plot units: mg, or dB
    private int count = 0;
    private boolean decibels = false;
    private String unit = "mg";
    private List<Orders.Source> lines = new ArrayList<>();
    private List<Mark> marks = new ArrayList<>();
    private double selectedHz = Double.NaN;
    private boolean live = false;
    private String emptyHint = "";
    private int curveColor = Theme.INK;

    // What is on screen right now (animated toward the targets).
    private float[] shown;
    private double fMaxTarget = 100, fMax = 100;
    private double yHiTarget = 20, yHi = 20, yLoTarget = 0, yLo = 0;
    private final Map<String, Float> lineShown = new HashMap<>();
    private long lastFrame = 0;

    // Finger cursor.
    private boolean cursorOn = false, dragging = false;
    private float cursorX, downX, downY;
    private long cursorHideAt = 0;
    private final int touchSlop;
    private Listener listener;

    private final float dp;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orderLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orderText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path fill = new Path();
    private int fillFor = 0;
    private float fillTop = -1, fillBottom = -1;

    public SpectrumView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        grid.setColor(Theme.LINE);
        grid.setStrokeWidth(1 * dp);
        axisText.setColor(Theme.MUTED);
        axisText.setTextSize(10 * dp);
        axisText.setTypeface(Theme.MONO);
        specLine.setStyle(Paint.Style.STROKE);
        specLine.setStrokeWidth(1.8f * dp);
        specLine.setStrokeJoin(Paint.Join.ROUND);
        specFill.setStyle(Paint.Style.FILL);
        orderLine.setStyle(Paint.Style.STROKE);
        orderLine.setStrokeWidth(1.5f * dp);
        orderLine.setPathEffect(new DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));
        orderText.setTextSize(11 * dp);
        orderText.setTypeface(Theme.BOLD);
        dot.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * dp);
        bubble.setStyle(Paint.Style.FILL);
        bubble.setColor(Theme.alpha(Theme.BG, 235));
        bubbleText.setTextSize(12 * dp);
        bubbleText.setTypeface(Theme.MONO_BOLD);
        hint.setTextSize(13 * dp);
        hint.setTypeface(Theme.MEDIUM);
        hint.setColor(Theme.MUTED);
        hint.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Spectrum. Drag to read a frequency, tap a peak to select it.");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /**
     * @param df        Hz per element of values
     * @param values    the curve in plot units (mg, or dB when decibels is true); null clears the chart
     * @param fTop      highest frequency to show
     * @param lines     expected order lines
     * @param marks     dots on the measured peaks
     * @param selected  frequency of the selected peak, or NaN
     */
    public void setPlot(double df, double[] values, int count, boolean decibels, String unit, int curveColor,
                        double fTop, List<Orders.Source> lines, List<Mark> marks, double selected,
                        boolean live, String emptyHint) {
        boolean reshape = values == null || shown == null || this.count != count
                || this.decibels != decibels || Math.abs(this.df - df) > 1e-9;
        this.df = df;
        this.values = values;
        this.count = values == null ? 0 : count;
        this.decibels = decibels;
        this.unit = unit;
        this.curveColor = curveColor;
        this.lines = lines != null ? lines : new ArrayList<>();
        this.marks = marks != null ? marks : new ArrayList<>();
        this.selectedHz = selected;
        this.live = live;
        this.emptyHint = emptyHint == null ? "" : emptyHint;
        fMaxTarget = Math.max(20, fTop);

        if (values != null) {
            double hi = -1e9;
            int kStart = Math.max(1, (int) Math.ceil(3 / df));
            for (int k = kStart; k < count && k * df <= fMaxTarget; k++) hi = Math.max(hi, values[k]);
            if (decibels) {
                double top = Math.ceil((hi + 6) / 10) * 10;
                // rise at once, sink slowly, so the scale doesn't pump
                yHiTarget = top > yHiTarget || reshape ? top : yHiTarget * 0.9 + top * 0.1;
                yLoTarget = yHiTarget - 60;
            } else {
                double top = Math.max(hi * 1.18, 2.0);
                yHiTarget = top > yHiTarget || reshape ? top : yHiTarget * 0.85 + top * 0.15;
                yLoTarget = 0;
            }
            if (reshape) {
                shown = new float[count];
                for (int k = 0; k < count; k++) shown[k] = (float) values[k];
                yHi = yHiTarget;
                yLo = yLoTarget;
                fMax = fMaxTarget;
            }
        } else {
            shown = null;
        }
        invalidate();
    }

    // ------------------------------------------------------------------ touch

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
                    // A tap: jump to the nearest peak if one is close, and select it.
                    cursorX = e.getX();
                    Mark m = nearestMark(cursorX, 22 * dp);
                    if (m != null) {
                        cursorX = xOf(m.hz);
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        if (listener != null) listener.onPickFrequency(m.hz);
                    }
                    cursorOn = true;
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

    private Mark nearestMark(float x, float within) {
        Mark best = null;
        float bestD = within;
        for (Mark m : marks) {
            if (m.hz > fMax) continue;
            float d = Math.abs(xOf(m.hz) - x);
            if (d < bestD) { bestD = d; best = m; }
        }
        return best;
    }

    // ------------------------------------------------------------------ drawing

    private float left() { return 8 * dp; }
    private float right() { return getWidth() - 8 * dp; }

    private float xOf(double f) {
        return (float) (left() + f / fMax * (right() - left()));
    }

    private double fOf(float x) {
        return Math.max(0, Math.min(fMax, (x - left()) / (right() - left()) * fMax));
    }

    private float yOf(double v, float top, float bottom) {
        double t = (v - yLo) / (yHi - yLo);
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return (float) (bottom - t * (bottom - top));
    }

    private String fmt(double v) {
        if (decibels) return String.format(Locale.US, "%.0f %s", v, unit);
        return String.format(Locale.US, v < 10 ? "%.1f %s" : "%.0f %s", v, unit);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
        lastFrame = now;
        float k = 1f - (float) Math.exp(-dt / 110f);
        boolean moving = false;

        // Glide the scales.
        if (Math.abs(fMaxTarget - fMax) > 0.2) { fMax += (fMaxTarget - fMax) * k; moving = true; } else fMax = fMaxTarget;
        if (Math.abs(yHiTarget - yHi) > Math.abs(yHiTarget) * 0.003 + 0.01) { yHi += (yHiTarget - yHi) * k; moving = true; } else yHi = yHiTarget;
        if (Math.abs(yLoTarget - yLo) > 0.05) { yLo += (yLoTarget - yLo) * k; moving = true; } else yLo = yLoTarget;

        float w = getWidth(), h = getHeight();
        float left = left(), right = right();
        float labelRowH = 14 * dp;

        // Order lines glide too. Lay their labels out in rows so they don't overlap.
        List<Orders.Source> vis = new ArrayList<>();
        for (Orders.Source s : lines) if (s.drawLine && s.hz > 0) vis.add(s);
        Collections.sort(vis, (a, b) -> Double.compare(a.hz, b.hz));
        Map<String, Float> next = new HashMap<>();
        float[] lineHz = new float[vis.size()];
        for (int i = 0; i < vis.size(); i++) {
            Orders.Source s = vis.get(i);
            Float cur = lineShown.get(s.shortName);
            float v = cur == null ? (float) s.hz : cur;
            if (Math.abs(s.hz - v) > 0.02) { v += (s.hz - v) * k; moving = true; } else v = (float) s.hz;
            next.put(s.shortName, v);
            lineHz[i] = v;
        }
        lineShown.clear();
        lineShown.putAll(next);

        List<Float> rowEnds = new ArrayList<>();
        float[] labelX = new float[vis.size()];
        int[] labelRow = new int[vis.size()];
        for (int i = 0; i < vis.size(); i++) {
            labelRow[i] = -1;
            if (lineHz[i] > fMax) continue;
            float x = xOf(lineHz[i]);
            float tw = orderText.measureText(vis.get(i).shortName) + 6 * dp;
            float lx = Math.max(left, Math.min(x - tw / 2, right - tw));
            int row = -1;
            for (int r = 0; r < rowEnds.size(); r++) if (rowEnds.get(r) < lx) { row = r; break; }
            if (row == -1) { row = rowEnds.size(); rowEnds.add(0f); }
            rowEnds.set(row, lx + tw);
            labelX[i] = lx;
            labelRow[i] = row;
        }
        float top = 6 * dp + Math.max(2, rowEnds.size()) * labelRowH + 4 * dp;
        float bottom = h - 20 * dp;

        // Grid and frequency axis.
        double step = fMax > 420 ? 100 : fMax > 240 ? 50 : fMax > 120 ? 20 : 10;
        double labelStep = fMax / step > 8 ? step * 2 : step;
        for (double f = 0; f <= fMax + 1e-6; f += step) {
            float x = xOf(f);
            c.drawLine(x, top, x, bottom, grid);
            if (Math.abs(f / labelStep - Math.round(f / labelStep)) > 1e-6) continue;
            String t = String.format(Locale.US, "%.0f", f);
            float tw = axisText.measureText(t);
            c.drawText(t, Math.max(left, Math.min(x - tw / 2, right - tw)), bottom + 14 * dp, axisText);
        }
        c.drawLine(left, bottom, right, bottom, grid);
        float mid = (top + bottom) / 2;
        grid.setAlpha(110);
        c.drawLine(left, mid, right, mid, grid);
        grid.setAlpha(255);
        if (shown != null) {
            c.drawText(fmt(yHi), left + 4 * dp, top + 12 * dp, axisText);
            c.drawText(fmt((yHi + yLo) / 2), left + 4 * dp, mid - 3 * dp, axisText);
        }
        c.drawText("Hz", right - axisText.measureText("Hz"), bottom - 4 * dp, axisText);

        // Curve.
        if (shown != null && values != null) {
            float tol = (float) ((yHi - yLo) * 0.004);
            path.reset();
            boolean started = false;
            float lastX = left;
            int n = Math.min(count, shown.length);
            for (int i = 0; i < n; i++) {
                float tv = (float) values[i];
                float d = tv - shown[i];
                if (Math.abs(d) > tol) { shown[i] += d * k; moving = true; } else shown[i] = tv;
                double f = i * df;
                if (f > fMax) continue;
                float x = xOf(f);
                float y = yOf(shown[i], top, bottom);
                if (!started) { path.moveTo(x, y); started = true; } else path.lineTo(x, y);
                lastX = x;
            }
            if (started) {
                if (fillFor != curveColor || fillTop != top || fillBottom != bottom) {
                    specFill.setShader(new LinearGradient(0, top, 0, bottom,
                            Theme.alpha(curveColor, 95), Theme.alpha(curveColor, 6), Shader.TileMode.CLAMP));
                    fillFor = curveColor;
                    fillTop = top;
                    fillBottom = bottom;
                }
                fill.set(path);
                fill.lineTo(lastX, bottom);
                fill.lineTo(left, bottom);
                fill.close();
                c.drawPath(fill, specFill);
                specLine.setColor(curveColor);
                c.drawPath(path, specLine);
            }
        } else {
            c.drawText(emptyHint, w / 2f, mid + 5 * dp, hint);
            if (live) {
                // A soft bar sweeping across while the first seconds of data come in.
                float t = (now % 1600) / 1600f;
                float x = left + t * (right - left);
                specFill.setShader(new LinearGradient(x - 40 * dp, 0, x, 0,
                        Theme.alpha(Theme.INK, 0), Theme.alpha(Theme.INK, 40), Shader.TileMode.CLAMP));
                fillFor = 0;
                c.drawRect(Math.max(left, x - 40 * dp), top, x, bottom, specFill);
            }
        }

        // Order lines.
        for (int i = 0; i < vis.size(); i++) {
            if (labelRow[i] < 0) continue;
            Orders.Source s = vis.get(i);
            int col = Theme.familyColor(s.family);
            orderLine.setColor(Theme.alpha(col, 200));
            orderText.setColor(col);
            float x = xOf(lineHz[i]);
            float ly = 6 * dp + (labelRow[i] + 1) * labelRowH - 3 * dp;
            c.drawLine(x, ly + 3 * dp, x, bottom, orderLine);
            c.drawText(s.shortName, labelX[i] + 3 * dp, ly, orderText);
        }

        // Dots on the measured peaks. The selected one gets a ring that pulses while live.
        for (Mark m : marks) {
            if (m.hz > fMax) continue;
            float x = xOf(m.hz), y = yOf(m.y, top, bottom);
            boolean sel = !Double.isNaN(selectedHz) && Math.abs(m.hz - selectedHz) < Math.max(1.5 * df, 0.6);
            if (sel) {
                float pulse = live ? 0.5f + 0.5f * (float) Math.sin(now / 300.0) : 0.4f;
                ring.setColor(Theme.alpha(m.color, Math.round(90 + 120 * (1 - pulse))));
                c.drawCircle(x, y, (7 + 4 * pulse) * dp, ring);
            }
            dot.setColor(Theme.BG);
            c.drawCircle(x, y, (sel ? 5.5f : 4.5f) * dp, dot);
            dot.setColor(m.color);
            c.drawCircle(x, y, (sel ? 4f : 3f) * dp, dot);
        }

        // Finger cursor with a read-out.
        if (cursorOn && now > cursorHideAt) cursorOn = false;
        if (cursorOn && shown != null && df > 0) {
            Mark snap = nearestMark(cursorX, 14 * dp);
            double f = snap != null ? snap.hz : fOf(cursorX);
            float x = xOf(f);
            double v;
            if (snap != null) v = snap.y;
            else {
                int i = Math.max(0, Math.min(count - 1, (int) Math.round(f / df)));
                v = values[i];
            }
            grid.setColor(Theme.alpha(Theme.INK, 170));
            c.drawLine(x, top, x, bottom, grid);
            grid.setColor(Theme.LINE);
            Orders.Source s = Orders.bestMatch(lines, f, df);
            String t1 = String.format(Locale.US, "%.1f Hz  %s", f, fmt(v));
            String t2 = s != null ? s.name : snap != null ? "no turning part" : "";
            float tw = Math.max(bubbleText.measureText(t1), bubbleText.measureText(t2)) + 16 * dp;
            float bh = (t2.isEmpty() ? 24 : 40) * dp;
            float bx = Math.max(left, Math.min(x - tw / 2, right - tw));
            float by = top + 2 * dp;
            c.drawRoundRect(bx, by, bx + tw, by + bh, 8 * dp, 8 * dp, bubble);
            ring.setColor(Theme.LINE);
            c.drawRoundRect(bx, by, bx + tw, by + bh, 8 * dp, 8 * dp, ring);
            bubbleText.setColor(Theme.INK);
            c.drawText(t1, bx + 8 * dp, by + 16 * dp, bubbleText);
            if (!t2.isEmpty()) {
                bubbleText.setColor(s != null ? Theme.familyColor(s.family) : Theme.MUTED);
                c.drawText(t2, bx + 8 * dp, by + 32 * dp, bubbleText);
            }
            if (cursorHideAt != Long.MAX_VALUE) moving = true;
        }

        if (moving || live) postInvalidateOnAnimation();
    }
}
