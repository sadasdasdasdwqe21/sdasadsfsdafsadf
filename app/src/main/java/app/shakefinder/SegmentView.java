package app.shakefinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** A two-way switch with a thumb that slides to the chosen side. */
public class SegmentView extends View {

    public interface Listener { void onSelected(int index); }

    private final float dp;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] labels;
    private int selected = 0;
    private float pos = 0; // animated 0..labels.length-1
    private long lastFrame = 0;
    private Listener listener;

    public SegmentView(Context c, String... labels) {
        super(c);
        this.labels = labels;
        dp = c.getResources().getDisplayMetrics().density;
        bg.setColor(Theme.SURFACE);
        thumb.setColor(Theme.SURFACE_HI);
        text.setTypeface(Theme.MEDIUM);
        text.setTextSize(14 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSelected(int index, boolean animate) {
        selected = index;
        if (!animate) pos = index;
        setContentDescription(labels[index] + " mode");
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (int) (44 * dp));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            int idx = Math.max(0, Math.min(labels.length - 1, (int) (e.getX() / (getWidth() / (float) labels.length))));
            if (idx != selected) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                setSelected(idx, true);
                if (listener != null) listener.onSelected(idx);
            }
            performClick();
        }
        return true;
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
        boolean moving = false;
        if (Math.abs(selected - pos) > 0.005f) {
            pos += (selected - pos) * (1f - (float) Math.exp(-dt / 90f));
            moving = true;
        } else pos = selected;

        float w = getWidth(), h = getHeight(), r = h / 2f;
        c.drawRoundRect(0, 0, w, h, r, r, bg);
        float seg = w / labels.length, pad = 4 * dp;
        float tx = pos * seg;
        c.drawRoundRect(tx + pad, pad, tx + seg - pad, h - pad, r - pad, r - pad, thumb);
        for (int i = 0; i < labels.length; i++) {
            float near = 1f - Math.min(1f, Math.abs(pos - i));
            text.setColor(Theme.mix(Theme.MUTED, Theme.INK, near));
            c.drawText(labels[i], seg * (i + 0.5f), h / 2f + text.getTextSize() * 0.35f, text);
        }
        if (moving) postInvalidateOnAnimation();
    }
}
