package app.shakefinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The strongest peaks as tappable rows: a tag for the part it matches, the
 * frequency, a bar that grows to its strength, and the value.
 */
public class PeakListView extends View {

    public interface Listener { void onPick(double hz); }

    public static final class Row {
        public final String tag;   // "2E", "1T", or "–"
        public final int color;
        public final double hz;
        public final String value; // "21.0 mg" or "+18 dB"
        public final double frac;  // bar length 0..1

        public Row(String tag, int color, double hz, String value, double frac) {
            this.tag = tag;
            this.color = color;
            this.hz = hz;
            this.value = value;
            this.frac = frac;
        }
    }

    private final float dp, rowH;
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Row> rows = new ArrayList<>();
    private float[] shown = new float[0];
    private double selectedHz = Double.NaN;
    private long lastFrame = 0;
    private Listener listener;

    public PeakListView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        rowH = 44 * dp;
        bg.setStyle(Paint.Style.FILL);
        bar.setStyle(Paint.Style.FILL);
        chip.setStyle(Paint.Style.FILL);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setRows(List<Row> rows, double selectedHz) {
        boolean sizeChanged = rows.size() != this.rows.size();
        this.rows = rows;
        this.selectedHz = selectedHz;
        if (shown.length != rows.size()) {
            float[] n = new float[rows.size()];
            System.arraycopy(shown, 0, n, 0, Math.min(shown.length, n.length));
            shown = n;
        }
        StringBuilder sb = new StringBuilder("Strongest peaks. ");
        for (Row r : rows) sb.append(String.format(Locale.US, "%.1f hertz, %s, %s. ", r.hz, r.value, r.tag));
        setContentDescription(sb.toString());
        if (sizeChanged) requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), (int) (rows.size() * rowH));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            int i = (int) (e.getY() / rowH);
            if (i >= 0 && i < rows.size()) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                if (listener != null) listener.onPick(rows.get(i).hz);
            }
            performClick();
            return true;
        }
        return super.onTouchEvent(e);
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
        float w = getWidth();
        float chipW = 46 * dp, hzW = 74 * dp, valW = 78 * dp;
        float barL = chipW + hzW + 10 * dp, barR = w - valW - 6 * dp;

        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            float want = (float) Math.max(0.02, Math.min(1, r.frac));
            if (Math.abs(want - shown[i]) > 0.004f) { shown[i] += (want - shown[i]) * k; moving = true; } else shown[i] = want;
            float y0 = i * rowH, cy = y0 + rowH / 2;
            boolean sel = !Double.isNaN(selectedHz) && Math.abs(r.hz - selectedHz) < 0.6;
            if (sel) {
                bg.setColor(Theme.SURFACE_HI);
                c.drawRoundRect(0, y0 + 2 * dp, w, y0 + rowH - 2 * dp, 8 * dp, 8 * dp, bg);
            }
            // Tag chip.
            chip.setColor(Theme.alpha(r.color, 46));
            c.drawRoundRect(4 * dp, cy - 12 * dp, chipW - 2 * dp, cy + 12 * dp, 12 * dp, 12 * dp, chip);
            text.setTypeface(Theme.BOLD);
            text.setTextSize(12.5f * dp);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(r.color);
            c.drawText(r.tag, (4 * dp + chipW - 2 * dp) / 2, cy + 4.5f * dp, text);
            // Frequency.
            text.setTypeface(Theme.MONO);
            text.setTextSize(13 * dp);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(Theme.INK);
            c.drawText(String.format(Locale.US, "%.1f Hz", r.hz), chipW + hzW, cy + 4.5f * dp, text);
            // Bar.
            bar.setColor(Theme.alpha(Theme.LINE, 160));
            c.drawRoundRect(barL, cy - 3 * dp, barR, cy + 3 * dp, 3 * dp, 3 * dp, bar);
            bar.setColor(r.color);
            c.drawRoundRect(barL, cy - 3 * dp, barL + (barR - barL) * shown[i], cy + 3 * dp, 3 * dp, 3 * dp, bar);
            // Value.
            text.setColor(sel ? Theme.INK : Theme.MUTED);
            c.drawText(r.value, w - 6 * dp, cy + 4.5f * dp, text);
        }
        if (moving) postInvalidateOnAnimation();
    }
}
