package app.shakefinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;

import java.util.Locale;

/** A five-zone strength bar with a marker that slides to the measured level. */
public class LevelMeterView extends View {

    private static final double MIN_MG = 1, MAX_MG = 300; // log scale

    private final float dp;
    private final Paint zone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();

    private double mg = Double.NaN;
    private float shown = 0;      // 0..1 along the bar
    private float presence = 0;
    private long lastFrame = 0;

    public LevelMeterView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        marker.setStyle(Paint.Style.FILL);
        label.setTypeface(Theme.MEDIUM);
        label.setTextSize(11 * dp);
    }

    /** NaN hides the marker. */
    public void setLevel(double mg) {
        this.mg = mg;
        if (!Double.isNaN(mg)) {
            int i = Theme.levelIndex(mg);
            setContentDescription(String.format(Locale.US, "Strength %s, %.0f milli g", Theme.LEVEL_NAME[i], mg));
        }
        invalidate();
    }

    static float position(double mg) {
        double v = Math.max(MIN_MG, Math.min(MAX_MG, mg));
        return (float) (Math.log(v / MIN_MG) / Math.log(MAX_MG / MIN_MG));
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (int) (40 * dp));
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(64, now - lastFrame);
        lastFrame = now;
        boolean has = !Double.isNaN(mg);
        boolean moving = false;
        float k = 1f - (float) Math.exp(-dt / 160f);
        if (has) {
            float want = position(mg);
            if (presence < 0.05f) shown = want;
            if (Math.abs(want - shown) > 0.002f) { shown += (want - shown) * k; moving = true; } else shown = want;
        }
        float wantP = has ? 1 : 0;
        if (Math.abs(wantP - presence) > 0.01f) { presence += (wantP - presence) * k; moving = true; } else presence = wantP;

        float w = getWidth();
        float barTop = 10 * dp, barH = 8 * dp, gap = 3 * dp;
        int active = has ? Theme.levelIndex(mg) : -1;
        float x0 = 0;
        for (int i = 0; i < Theme.LEVEL_NAME.length; i++) {
            float x1 = i < Theme.LEVEL_MG.length ? position(Theme.LEVEL_MG[i]) * w : w;
            boolean on = i == active;
            zone.setColor(Theme.alpha(Theme.LEVEL_COLOR[i], on ? Math.round(110 + 145 * presence) : 70));
            c.drawRoundRect(x0, barTop, x1 - gap, barTop + barH, barH / 2, barH / 2, zone);
            label.setColor(on ? Theme.LEVEL_COLOR[i] : Theme.alpha(Theme.MUTED, 150));
            label.setTextAlign(Paint.Align.LEFT);
            String name = Theme.LEVEL_NAME[i];
            if (label.measureText(name) < x1 - gap - x0) c.drawText(name, x0 + 1 * dp, barTop + barH + 15 * dp, label);
            x0 = x1;
        }
        if (presence > 0.01f) {
            float x = Math.max(5 * dp, Math.min(w - 5 * dp, shown * w));
            marker.setColor(Theme.alpha(Theme.INK, Math.round(255 * presence)));
            tri.reset();
            tri.moveTo(x - 5 * dp, 1 * dp);
            tri.lineTo(x + 5 * dp, 1 * dp);
            tri.lineTo(x, barTop - 1 * dp);
            tri.close();
            c.drawPath(tri, marker);
            c.drawRoundRect(x - 1.5f * dp, barTop - 1 * dp, x + 1.5f * dp, barTop + barH + 1 * dp, dp, dp, marker);
        }
        if (moving) postInvalidateOnAnimation();
    }
}
