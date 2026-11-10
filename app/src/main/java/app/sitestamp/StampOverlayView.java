package app.sitestamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.List;

/**
 * Sits on top of the camera preview. In photo mode it shows the stamp where it will
 * land on the photo. In video mode the stamp is already inside the camera picture,
 * so this only draws the grid and the focus ring.
 */
final class StampOverlayView extends View {

    private final StampRenderer renderer = new StampRenderer();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<StampText.Line> lines;
    private StampRenderer.Style style;
    private int rotation;
    private boolean showStamp = true;
    private boolean showGrid;
    private float ringX, ringY;
    private long ringAt;

    StampOverlayView(Context c) {
        super(c);
        gridPaint.setColor(0x66FFFFFF);
        gridPaint.setStrokeWidth(Ui.dp(c, 1));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(Ui.dp(c, 2));
        ringPaint.setColor(StampRenderer.ACCENT);
    }

    void setStamp(List<StampText.Line> lines, StampRenderer.Style style, int rotationCw) {
        this.lines = lines;
        this.style = style;
        this.rotation = rotationCw;
        invalidate();
    }

    void setShowStamp(boolean show) {
        showStamp = show;
        invalidate();
    }

    void setShowGrid(boolean show) {
        showGrid = show;
        invalidate();
    }

    void showFocusRing(float x, float y) {
        ringX = x;
        ringY = y;
        ringAt = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (showGrid) {
            canvas.drawLine(w / 3f, 0, w / 3f, h, gridPaint);
            canvas.drawLine(2 * w / 3f, 0, 2 * w / 3f, h, gridPaint);
            canvas.drawLine(0, h / 3f, w, h / 3f, gridPaint);
            canvas.drawLine(0, 2 * h / 3f, w, 2 * h / 3f, gridPaint);
        }
        if (showStamp && lines != null && style != null) {
            renderer.draw(canvas, w, h, null, rotation, false, lines, style);
        }
        long age = SystemClock.uptimeMillis() - ringAt;
        if (ringAt != 0 && age < 900) {
            float t = age / 900f;
            float r = Ui.dp(getContext(), 34) * (1.15f - 0.25f * Math.min(1f, t * 3f));
            ringPaint.setAlpha((int) (255 * (1f - t * t)));
            canvas.drawCircle(ringX, ringY, r, ringPaint);
            postInvalidateOnAnimation();
        }
    }
}
