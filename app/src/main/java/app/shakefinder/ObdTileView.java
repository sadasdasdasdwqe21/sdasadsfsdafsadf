package app.shakefinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.util.Locale;

/**
 * One live OBD value: its name, the number, and a small graph of the last
 * minute or so. Tap it to see the lowest and highest values seen.
 */
public class ObdTileView extends View {

    private static final int HIST = 120;

    private final Elm327.Pid pid;
    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] hist = new float[HIST];
    private int n = 0, head = 0;
    private double value = Double.NaN, shown = 0, min = Double.NaN, max = Double.NaN;
    private boolean showRange = false, fresh = false;
    private long lastFrame = 0, lastPush = 0;

    public ObdTileView(Context c, Elm327.Pid pid) {
        super(c);
        this.pid = pid;
        dp = c.getResources().getDisplayMetrics().density;
        bg.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.6f * dp);
        line.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setOnClickListener(v -> {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            showRange = !showRange;
            invalidate();
        });
    }

    public int pidId() {
        return pid.id;
    }

    public void push(double v) {
        if (Double.isNaN(v)) return;
        if (Double.isNaN(value)) shown = v;
        value = v;
        min = Double.isNaN(min) ? v : Math.min(min, v);
        max = Double.isNaN(max) ? v : Math.max(max, v);
        hist[head] = (float) v;
        head = (head + 1) % HIST;
        if (n < HIST) n++;
        lastPush = SystemClock.uptimeMillis();
        fresh = true;
        setContentDescription(pid.name + " " + pid.format(v) + " " + pid.unit);
        invalidate();
    }

    public void reset() {
        n = 0;
        head = 0;
        value = Double.NaN;
        min = Double.NaN;
        max = Double.NaN;
        fresh = false;
        invalidate();
    }

    /** Amber or red when a value is somewhere it shouldn't be. */
    private int tone() {
        if (Double.isNaN(value)) return Theme.MUTED;
        double v = value;
        switch (pid.id) {
            case 0x06: case 0x08:
                return Math.abs(v) > 20 ? Theme.BAD : Math.abs(v) > 12 ? Theme.WARN : Theme.OK;
            case 0x07: case 0x09:
                return Math.abs(v) > 15 ? Theme.BAD : Math.abs(v) > 8 ? Theme.WARN : Theme.OK;
            case 0x05:
                return v > 108 ? Theme.BAD : v > 102 ? Theme.WARN : v < 70 ? Theme.SPEED : Theme.OK;
            case 0x5C:
                return v > 130 ? Theme.BAD : v > 118 ? Theme.WARN : Theme.OK;
            case 0x42:
                return v < 11.8 || v > 15.2 ? Theme.BAD : v < 12.4 ? Theme.WARN : Theme.OK;
            case 0x0C:
                return Theme.ENGINE;
            case 0x0D:
                return Theme.SPEED;
            default:
                return Theme.PROPSHAFT;
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (int) (92 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
        lastFrame = now;
        boolean moving = false;
        if (!Double.isNaN(value)) {
            double d = value - shown;
            double eps = Math.pow(10, -pid.decimals) * 0.4;
            if (Math.abs(d) > eps) { shown += d * (1 - Math.exp(-dt / 120.0)); moving = true; } else shown = value;
        }
        if (fresh && now - lastPush > 4000) fresh = false; // gone quiet

        float w = getWidth(), h = getHeight();
        int tone = tone();
        bg.setColor(Theme.SURFACE_HI);
        c.drawRoundRect(0, 0, w, h, 12 * dp, 12 * dp, bg);
        // Flash the edge briefly on each new value.
        float flash = Math.max(0, 1 - (now - lastPush) / 500f);
        if (flash > 0 && n > 0) {
            line.setColor(Theme.alpha(tone, Math.round(120 * flash)));
            c.drawRoundRect(1 * dp, 1 * dp, w - 1 * dp, h - 1 * dp, 12 * dp, 12 * dp, line);
            moving = true;
        }

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(Theme.MEDIUM);
        text.setTextSize(11.5f * dp);
        text.setColor(Theme.MUTED);
        c.drawText(pid.name, 12 * dp, 20 * dp, text);

        text.setTypeface(Theme.MONO_BOLD);
        text.setTextSize(24 * dp);
        text.setColor(fresh ? Theme.INK : Theme.MUTED);
        String v = Double.isNaN(value) ? "–" : pid.format(shown);
        c.drawText(v, 12 * dp, 50 * dp, text);
        float vw = text.measureText(v);
        text.setTypeface(Theme.MEDIUM);
        text.setTextSize(12 * dp);
        text.setColor(Theme.MUTED);
        c.drawText(pid.unit, 12 * dp + vw + 5 * dp, 50 * dp, text);

        if (showRange && !Double.isNaN(min)) {
            text.setTextSize(11 * dp);
            text.setTypeface(Theme.MONO);
            c.drawText(String.format(Locale.US, "low %s  high %s", pid.format(min), pid.format(max)), 12 * dp, h - 12 * dp, text);
        } else if (n >= 2) {
            // Graph, scaled to its own range but never tighter than 4% of the usual span.
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float x = hist[(head - n + i + HIST) % HIST];
                lo = Math.min(lo, x);
                hi = Math.max(hi, x);
            }
            float minSpan = (float) ((pid.hi - pid.lo) * 0.04);
            if (hi - lo < minSpan) {
                float m = (hi + lo) / 2;
                lo = m - minSpan / 2;
                hi = m + minSpan / 2;
            }
            float gl = 12 * dp, gr = w - 12 * dp, gt = 60 * dp, gb = h - 10 * dp;
            path.reset();
            for (int i = 0; i < n; i++) {
                float x = hist[(head - n + i + HIST) % HIST];
                float px = gl + (gr - gl) * i / (float) (HIST - 1);
                float py = gb - (x - lo) / (hi - lo) * (gb - gt);
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            line.setColor(Theme.alpha(tone, fresh ? 230 : 110));
            c.drawPath(path, line);
        }
        if (moving) postInvalidateOnAnimation();
    }
}
