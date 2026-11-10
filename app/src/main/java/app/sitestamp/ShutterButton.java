package app.sitestamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** The big round button. White for a photo, red for video, a red square while recording. */
final class ShutterButton extends View {
    static final int PHOTO = 0;
    static final int VIDEO = 1;
    static final int RECORDING = 2;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int mode = PHOTO;
    private boolean down;

    ShutterButton(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Ui.TEXT);
        setClickable(true);
        setFocusable(true);
    }

    void setMode(int mode) {
        this.mode = mode;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            down = true;
            invalidate();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            down = false;
            invalidate();
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        float w = r * 0.085f;
        ring.setStrokeWidth(w);
        ring.setAlpha(isEnabled() ? 255 : 110);
        c.drawCircle(cx, cy, r - w, ring);
        float inner = (r - w * 2.6f) * (down ? 0.90f : 1f);
        fill.setColor(mode == PHOTO ? Ui.TEXT : Ui.RED);
        fill.setAlpha(isEnabled() ? 255 : 110);
        if (mode == RECORDING) {
            float s = r * 0.36f;
            rect.set(cx - s, cy - s, cx + s, cy + s);
            c.drawRoundRect(rect, s * 0.3f, s * 0.3f, fill);
        } else {
            c.drawCircle(cx, cy, inner, fill);
        }
    }
}
