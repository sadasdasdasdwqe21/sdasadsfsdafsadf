package app.sitestamp;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the stamp. The same code draws it on the live preview, on a photo and on
 * every video frame, so all three look the same.
 *
 * The picture it draws on may be lying on its side (camera sensors usually are), so
 * the caller says how far the picture must be turned clockwise to stand upright.
 * One instance per thread.
 */
final class StampRenderer {

    static final int POS_BOTTOM_LEFT = 0;
    static final int POS_BOTTOM_RIGHT = 1;
    static final int POS_TOP_LEFT = 2;
    static final int POS_TOP_RIGHT = 3;

    static final int ACCENT = 0xFFFFB300;

    static final class Style {
        int position = POS_BOTTOM_LEFT;
        float scale = 1f;
        int color = 0xFFFFFFFF;
        boolean box = true;
    }

    private static final class Row {
        String text;
        int role;
        float size;
        float width;
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final Typeface regular = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final List<Row> rows = new ArrayList<>();

    StampRenderer() {
        boxPaint.setColor(0x80000000);
        barPaint.setColor(ACCENT);
    }

    /**
     * @param bufW       width of the picture as stored
     * @param bufH       height of the picture as stored
     * @param crop       the part of the picture that will be kept, or null for all of it
     * @param rotationCw degrees the stored picture is turned clockwise to stand upright
     * @param flip       true when the picture will later be mirrored left to right
     */
    void draw(Canvas canvas, int bufW, int bufH, Rect crop, int rotationCw, boolean flip,
            List<StampText.Line> lines, Style style) {
        if (lines == null || lines.isEmpty() || style == null) return;
        int cl = crop == null ? 0 : crop.left;
        int ct = crop == null ? 0 : crop.top;
        int cw = crop == null ? bufW : crop.width();
        int ch = crop == null ? bufH : crop.height();
        if (cw <= 0 || ch <= 0) return;
        int r = ((rotationCw % 360) + 360) % 360;
        r = ((r + 45) / 90) * 90 % 360;
        boolean swap = r == 90 || r == 270;
        float uw = swap ? ch : cw;
        float uh = swap ? cw : ch;

        matrix.reset();
        if (flip) {
            matrix.postScale(-1, 1);
            matrix.postTranslate(uw, 0);
        }
        matrix.postRotate(-r);
        if (r == 90) {
            matrix.postTranslate(0, ch);
        } else if (r == 180) {
            matrix.postTranslate(cw, ch);
        } else if (r == 270) {
            matrix.postTranslate(cw, 0);
        }
        matrix.postTranslate(cl, ct);

        canvas.save();
        canvas.concat(matrix);
        drawUpright(canvas, uw, uh, lines, style);
        canvas.restore();
    }

    private void drawUpright(Canvas c, float uw, float uh, List<StampText.Line> lines, Style st) {
        float base = Math.min(uw, uh);
        float size = base * 0.030f * st.scale;
        float margin = base * 0.035f;
        float padX = size * 0.60f;
        float padY = size * 0.50f;
        float barW = size * 0.22f;
        float gap = size * 0.50f;
        float maxText = uw - 2 * margin - 2 * padX - barW - gap;
        if (maxText < size * 4) return;

        rows.clear();
        for (StampText.Line line : lines) {
            float s = sizeFor(line.role, size);
            setPaint(line.role, s, st);
            wrap(line.text, line.role, s, maxText);
        }
        if (rows.isEmpty()) return;

        float blockW = 0, blockH = 0;
        for (Row row : rows) {
            blockW = Math.max(blockW, row.width);
            blockH += row.size * 1.30f;
        }
        float boxW = blockW + 2 * padX + barW + gap;
        float boxH = blockH + 2 * padY;
        boolean right = st.position == POS_BOTTOM_RIGHT || st.position == POS_TOP_RIGHT;
        boolean top = st.position == POS_TOP_LEFT || st.position == POS_TOP_RIGHT;
        float left = right ? uw - margin - boxW : margin;
        float topY = top ? margin : uh - margin - boxH;

        if (st.box) {
            rect.set(left, topY, left + boxW, topY + boxH);
            float rad = size * 0.40f;
            c.drawRoundRect(rect, rad, rad, boxPaint);
        }
        float barX = right ? left + boxW - padX - barW : left + padX;
        rect.set(barX, topY + padY, barX + barW, topY + boxH - padY);
        c.drawRoundRect(rect, barW / 2, barW / 2, barPaint);

        float textX = right ? barX - gap : barX + barW + gap;
        textPaint.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
        float y = topY + padY;
        for (Row row : rows) {
            setPaint(row.role, row.size, st);
            textPaint.getFontMetrics(metrics);
            float lineH = row.size * 1.30f;
            float baseline = y + (lineH - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent;
            c.drawText(row.text, textX, baseline, textPaint);
            y += lineH;
        }
    }

    private static float sizeFor(int role, float size) {
        if (role == StampText.ROLE_PROJECT) return size * 1.28f;
        if (role == StampText.ROLE_COMPANY) return size * 1.02f;
        return size * 0.92f;
    }

    private void setPaint(int role, float size, Style st) {
        textPaint.setTextSize(size);
        textPaint.setTypeface(role == StampText.ROLE_NORMAL ? regular : bold);
        textPaint.setColor(st.color);
        textPaint.setShadowLayer(size * (st.box ? 0.06f : 0.14f), 0, size * 0.05f, 0xF0000000);
    }

    /** Splits one line into rows that fit, breaking at spaces where it can. */
    private void wrap(String text, int role, float size, float maxWidth) {
        String rest = text;
        int guard = 0;
        while (!rest.isEmpty() && guard++ < 6) {
            int n = textPaint.breakText(rest, true, maxWidth, null);
            if (n <= 0) break;
            if (n < rest.length()) {
                int space = rest.lastIndexOf(' ', n);
                if (space > 0) n = space;
            }
            String part = rest.substring(0, n).trim();
            rest = rest.substring(n).trim();
            if (part.isEmpty()) continue;
            Row row = new Row();
            row.text = part;
            row.role = role;
            row.size = size;
            row.width = textPaint.measureText(part);
            rows.add(row);
        }
    }
}
