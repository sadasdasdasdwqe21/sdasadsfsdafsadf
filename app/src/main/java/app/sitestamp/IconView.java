package app.sitestamp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** The small drawn icons on the camera screen. Each is drawn in a 24 by 24 box. */
final class IconView extends View {
    static final int FLASH = 0;
    static final int TIMER = 1;
    static final int GRID = 2;
    static final int SWITCH = 3;
    static final int SETTINGS = 4;
    static final int PIN = 5;

    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private boolean active;
    private boolean crossed;
    private String badge = "";

    IconView(Context c, int kind) {
        super(c);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        label.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        setFocusable(true);
    }

    /** @param active amber instead of white; crossed draws a line through; badge is a tiny label */
    void setState(boolean active, boolean crossed, String badge) {
        this.active = active;
        this.crossed = crossed;
        this.badge = badge == null ? "" : badge;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float box = Math.min(getWidth(), getHeight()) * 0.52f;
        float u = box / 24f;
        float ox = (getWidth() - box) / 2f, oy = (getHeight() - box) / 2f;
        int color = !isEnabled() ? 0x55FFFFFF : active ? Ui.ACCENT : 0xFFFFFFFF;
        stroke.setColor(color);
        stroke.setStrokeWidth(1.9f * u);
        fill.setColor(color);
        label.setColor(color);
        label.setTextSize(9.5f * u);
        c.save();
        c.translate(ox, oy);
        c.scale(u, u);
        stroke.setStrokeWidth(1.9f);
        switch (kind) {
            case FLASH:
                path.rewind();
                path.moveTo(13.5f, 1.5f);
                path.lineTo(5, 13.5f);
                path.lineTo(11, 13.5f);
                path.lineTo(10, 22.5f);
                path.lineTo(19, 10f);
                path.lineTo(13, 10f);
                path.close();
                c.drawPath(path, active ? fill : stroke);
                break;
            case TIMER:
                c.drawCircle(12, 13.5f, 8, stroke);
                c.drawLine(9.5f, 2, 14.5f, 2, stroke);
                c.drawLine(12, 13.5f, 12, 8.5f, stroke);
                break;
            case GRID:
                rect.set(3.5f, 3.5f, 20.5f, 20.5f);
                c.drawRoundRect(rect, 2, 2, stroke);
                c.drawLine(9.2f, 3.5f, 9.2f, 20.5f, stroke);
                c.drawLine(14.8f, 3.5f, 14.8f, 20.5f, stroke);
                c.drawLine(3.5f, 9.2f, 20.5f, 9.2f, stroke);
                c.drawLine(3.5f, 14.8f, 20.5f, 14.8f, stroke);
                break;
            case SWITCH:
                rect.set(2.5f, 6.5f, 21.5f, 20f);
                c.drawRoundRect(rect, 3, 3, stroke);
                c.drawLine(8.5f, 6.5f, 10f, 4f, stroke);
                c.drawLine(10f, 4f, 14f, 4f, stroke);
                c.drawLine(14f, 4f, 15.5f, 6.5f, stroke);
                rect.set(8.2f, 9.4f, 15.8f, 17f);
                c.drawArc(rect, 20, 250, false, stroke);
                path.rewind();
                path.moveTo(15.9f, 9.2f);
                path.lineTo(16.6f, 13.2f);
                path.lineTo(12.9f, 11.9f);
                path.close();
                c.drawPath(path, fill);
                break;
            case SETTINGS:
                c.drawLine(3.5f, 6.5f, 20.5f, 6.5f, stroke);
                c.drawLine(3.5f, 12f, 20.5f, 12f, stroke);
                c.drawLine(3.5f, 17.5f, 20.5f, 17.5f, stroke);
                fill.setColor(Ui.BG);
                c.drawCircle(9, 6.5f, 2.6f, fill);
                c.drawCircle(15.5f, 12f, 2.6f, fill);
                c.drawCircle(8, 17.5f, 2.6f, fill);
                c.drawCircle(9, 6.5f, 2.6f, stroke);
                c.drawCircle(15.5f, 12f, 2.6f, stroke);
                c.drawCircle(8, 17.5f, 2.6f, stroke);
                break;
            case PIN:
            default:
                path.rewind();
                path.moveTo(12, 22);
                path.lineTo(6.6f, 13.2f);
                rect.set(5.5f, 2.5f, 18.5f, 15.5f);
                path.arcTo(rect, 148, 244, false);
                path.close();
                c.drawPath(path, stroke);
                fill.setColor(color);
                c.drawCircle(12, 9, 2.2f, fill);
                break;
        }
        if (crossed) {
            c.drawLine(3, 21, 21, 3, stroke);
        }
        c.restore();
        if (!badge.isEmpty()) {
            c.drawText(badge, ox + box + 4.5f * u, oy + box + 1.5f * u, label);
        }
    }
}
