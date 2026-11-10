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

    static final int START = 0;   // left, or top
    static final int MIDDLE = 1;
    static final int END = 2;     // right, or bottom

    static final int BOX_NONE = 0;
    static final int BOX_DARK = 1;
    static final int BOX_LIGHT = 2;

    static final int ACCENT = 0xFFFFB300;

    static final class Style {
        /** Left, middle or right. */
        int h = START;
        /** Top, middle or bottom. */
        int v = END;
        float scale = 1f;
        int color = 0xFFFFFFFF;
        int box = BOX_DARK;
        /** Gap to the picture's edge, as a share of the picture's short side. */
        float margin = 0.035f;
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
        float margin = base * Math.max(0f, Math.min(0.2f, st.margin));
        float size = base * 0.030f * st.scale;
        float padX = 0, padY = 0, barW = 0, gap = 0, boxW = 0, boxH = 0;

        // Lay the text out. If it comes out taller than the picture, shrink it and try again.
        for (int attempt = 0; attempt < 4; attempt++) {
            padX = size * 0.60f;
            padY = size * 0.50f;
            barW = size * 0.22f;
            gap = size * 0.50f;
            float maxText = uw - 2 * margin - 2 * padX - barW - gap;
            if (maxText < size * 4) {
                size *= 0.8f;
                continue;
            }
            rows.clear();
            for (StampText.Line line : lines) {
                float s = sizeFor(line.role, size);
                setPaint(line.role, s, st);
                wrap(line.text, line.role, s, maxText);
            }
            float blockW = 0, blockH = 0;
            for (Row row : rows) {
                blockW = Math.max(blockW, row.width);
                blockH += row.size * 1.30f;
            }
            boxW = blockW + 2 * padX + barW + gap;
            boxH = blockH + 2 * padY;
            float room = uh - 2 * margin;
            if (boxH <= room || room <= 0) break;
            size *= Math.max(0.5f, room / boxH * 0.98f);
        }
        if (rows.isEmpty()) return;

        boolean right = st.h == END;
        float left = st.h == START ? margin : right ? uw - margin - boxW : (uw - boxW) / 2f;
        float topY = st.v == START ? margin : st.v == END ? uh - margin - boxH
                : (uh - boxH) / 2f;

        if (st.box != BOX_NONE) {
            boxPaint.setColor(st.box == BOX_LIGHT ? 0xD9FFFFFF : 0x80000000);
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
        if (st.box == BOX_LIGHT) {
            textPaint.clearShadowLayer();
        } else {
            textPaint.setShadowLayer(size * (st.box == BOX_DARK ? 0.06f : 0.14f), 0,
                    size * 0.05f, 0xF0000000);
        }
    }

    /** Splits one line into rows that fit, breaking at spaces where it can. */
    private void wrap(String text, int role, float size, float maxWidth) {
        String rest = text;
        int guard = 0;
        while (!rest.isEmpty() && guard++ < 8) {
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
