package app.shakefinder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Draws the measured spectrum with the expected order lines on top. */
public class SpectrumView extends View {

    private Analysis.Spectrum spectrum;
    private List<Orders.Source> lines = new ArrayList<>();
    private double peakHz = Double.NaN;
    private double fMax = 100;
    private double yMax = 0.02; // m/s², smoothed so the plot doesn't jump

    private final float dp;
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orderLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orderText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    public SpectrumView(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        grid.setColor(Theme.LINE);
        grid.setStrokeWidth(1 * dp);
        axisText.setColor(Theme.MUTED);
        axisText.setTextSize(10 * dp);
        axisText.setTypeface(Typeface.MONOSPACE);
        specLine.setColor(Theme.INK);
        specLine.setStyle(Paint.Style.STROKE);
        specLine.setStrokeWidth(1.6f * dp);
        specLine.setStrokeJoin(Paint.Join.ROUND);
        specFill.setColor(Theme.INK);
        specFill.setAlpha(40);
        specFill.setStyle(Paint.Style.FILL);
        orderLine.setStyle(Paint.Style.STROKE);
        orderLine.setStrokeWidth(1.5f * dp);
        orderLine.setPathEffect(new DashPathEffect(new float[]{5 * dp, 4 * dp}, 0));
        orderText.setTextSize(11 * dp);
        orderText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        peakPaint.setColor(Theme.INK);
        peakPaint.setStyle(Paint.Style.FILL);
        setBackgroundColor(Theme.SURFACE);
        setContentDescription("Vibration spectrum");
    }

    public void setData(Analysis.Spectrum sp, List<Orders.Source> lines, double peakHz) {
        this.spectrum = sp;
        this.lines = lines != null ? lines : new ArrayList<>();
        this.peakHz = peakHz;

        double top = 60;
        for (Orders.Source s : this.lines) if (s.drawLine && s.family == Orders.Family.ENGINE && s.hz * 1.1 > top) top = s.hz * 1.1;
        if (!Double.isNaN(peakHz)) top = Math.max(top, peakHz * 1.2);
        top = Math.min(top, 200);
        if (sp != null) top = Math.min(top, sp.fs / 2);
        double step = top > 120 ? 40 : 20;
        fMax = Math.max(step, Math.ceil(top / step) * step);
        if (sp != null && fMax > sp.fs / 2) fMax = sp.fs / 2;

        if (sp != null) {
            double m = 0;
            int kStart = Math.max(1, (int) Math.ceil(3 / sp.df));
            for (int k = kStart; k < sp.amp.length && k * sp.df <= fMax; k++) m = Math.max(m, sp.amp[k]);
            double target = Math.max(m * 1.15, 0.005);
            yMax = target > yMax ? target : yMax * 0.8 + target * 0.2;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float left = 8 * dp, right = w - 8 * dp;
        float labelRowH = 14 * dp;

        // Lay out order labels in rows so they don't overlap.
        List<Orders.Source> shown = new ArrayList<>();
        for (Orders.Source s : lines) if (s.drawLine && s.hz > 0 && s.hz <= fMax) shown.add(s);
        Collections.sort(shown, (a, b) -> Double.compare(a.hz, b.hz));
        List<Float> rowEnds = new ArrayList<>();
        float[] labelX = new float[shown.size()];
        int[] labelRow = new int[shown.size()];
        for (int i = 0; i < shown.size(); i++) {
            Orders.Source s = shown.get(i);
            float x = xOf(s.hz, left, right);
            float tw = orderText.measureText(s.shortName) + 6 * dp;
            float lx = Math.max(left, Math.min(x - tw / 2, right - tw));
            int row = -1;
            for (int r = 0; r < rowEnds.size(); r++) if (rowEnds.get(r) < lx) { row = r; break; }
            if (row == -1) { row = rowEnds.size(); rowEnds.add(0f); }
            rowEnds.set(row, lx + tw);
            labelX[i] = lx;
            labelRow[i] = row;
        }
        float top = 6 * dp + Math.max(1, rowEnds.size()) * labelRowH + 4 * dp;
        float bottom = h - 20 * dp;

        // Grid and frequency axis.
        double step = fMax > 120 ? 20 : 10;
        double labelStep = fMax / step > 8 ? step * 2 : step;
        for (double f = 0; f <= fMax + 1e-6; f += step) {
            float x = xOf(f, left, right);
            c.drawLine(x, top, x, bottom, grid);
            if (Math.abs(f / labelStep - Math.round(f / labelStep)) > 1e-6) continue;
            String t = String.format(Locale.US, "%.0f", f);
            float tw = axisText.measureText(t);
            c.drawText(t, Math.max(left, Math.min(x - tw / 2, right - tw)), bottom + 14 * dp, axisText);
        }
        c.drawLine(left, bottom, right, bottom, grid);
        String yLabel = String.format(Locale.US, "%.0f mg", yMax / 9.80665 * 1000);
        c.drawText(yLabel, left + 4 * dp, top + 12 * dp, axisText);
        String hz = "Hz";
        c.drawText(hz, right - axisText.measureText(hz), bottom - 4 * dp, axisText);

        // Spectrum.
        if (spectrum != null) {
            path.reset();
            boolean started = false;
            float lastX = left;
            for (int k = 0; k < spectrum.amp.length; k++) {
                double f = k * spectrum.df;
                if (f > fMax) break;
                float x = xOf(f, left, right);
                float y = (float) (bottom - Math.min(1.0, spectrum.amp[k] / yMax) * (bottom - top));
                if (!started) { path.moveTo(x, bottom); path.lineTo(x, y); started = true; }
                else path.lineTo(x, y);
                lastX = x;
            }
            if (started) {
                Path fill = new Path(path);
                fill.lineTo(lastX, bottom);
                fill.close();
                c.drawPath(fill, specFill);
                c.drawPath(path, specLine);
            }
        }

        // Order lines.
        for (int i = 0; i < shown.size(); i++) {
            Orders.Source s = shown.get(i);
            int col = Theme.familyColor(s.family);
            orderLine.setColor(col);
            orderText.setColor(col);
            float x = xOf(s.hz, left, right);
            float ly = 6 * dp + (labelRow[i] + 1) * labelRowH - 3 * dp;
            c.drawLine(x, ly + 3 * dp, x, bottom, orderLine);
            c.drawText(s.shortName, labelX[i] + 3 * dp, ly, orderText);
        }

        // Measured peak marker.
        if (!Double.isNaN(peakHz) && peakHz <= fMax) {
            float x = xOf(peakHz, left, right);
            Path tri = new Path();
            tri.moveTo(x - 6 * dp, bottom + 1 * dp);
            tri.lineTo(x + 6 * dp, bottom + 1 * dp);
            tri.lineTo(x, bottom - 8 * dp);
            tri.close();
            c.drawPath(tri, peakPaint);
        }
    }

    private float xOf(double f, float left, float right) {
        return (float) (left + f / fMax * (right - left));
    }
}
