package app.shakefinder;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {

    private static final int CAP = 16384;          // ring buffer: ~30 s at 500 Hz
    private static final double WINDOW_S = 4.0;    // seconds analysed each update
    private static final long UPDATE_MS = 500;
    private static final double MATCH_TOL = 0.06;  // ±6%
    private static final double G = 9.80665;

    // Sensor ring buffer (written on the sensor thread, read on the UI thread).
    private final Object lock = new Object();
    private final long[] tBuf = new long[CAP];
    private final float[] xBuf = new float[CAP], yBuf = new float[CAP], zBuf = new float[CAP];
    private int head = 0, count = 0;

    // Snapshot arrays reused for each analysis.
    private final long[] st = new long[CAP];
    private final float[] sx = new float[CAP], sy = new float[CAP], sz = new float[CAP];

    private SensorManager sensorManager;
    private Sensor accel;
    private boolean running = false, frozen = false;
    private Analysis.Spectrum lastSpectrum;
    private List<Analysis.Peak> lastPeaks = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (running && !frozen) analyze();
            if (running) handler.postDelayed(this, UPDATE_MS);
        }
    };

    private SharedPreferences prefs;
    private SpectrumView spectrumView;
    private TextView status, chip, resultTitle, resultWhat, resultCheck, peaksText;
    private EditText rpmIn, kmhIn, tyreIn, fdIn;
    private Button startBtn, freezeBtn;
    private boolean loading = false;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("shake", MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.BG);
        setContentView(buildUi());
        loadInputs();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopMeasuring();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------- sensor

    @Override
    public void onSensorChanged(SensorEvent e) {
        synchronized (lock) {
            tBuf[head] = e.timestamp;
            xBuf[head] = e.values[0];
            yBuf[head] = e.values[1];
            zBuf[head] = e.values[2];
            head = (head + 1) % CAP;
            if (count < CAP) count++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void startMeasuring() {
        if (accel == null) {
            status.setText("This phone has no accelerometer the app can read.");
            return;
        }
        synchronized (lock) { head = 0; count = 0; }
        lastSpectrum = null;
        lastPeaks = new ArrayList<>();
        frozen = false;
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST);
        running = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, UPDATE_MS);
        updateButtons();
        refresh();
    }

    private void stopMeasuring() {
        if (!running) return;
        running = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        handler.removeCallbacks(tick);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateButtons();
        refresh();
    }

    /** Copies the last WINDOW_S seconds of samples, oldest first. */
    private int snapshot() {
        synchronized (lock) {
            if (count < 2) return 0;
            int newest = (head - 1 + CAP) % CAP;
            long tEnd = tBuf[newest];
            long tStart = tEnd - (long) (WINDOW_S * 1e9);
            int n = 0;
            int idx = newest;
            while (n < count && tBuf[idx] >= tStart) {
                n++;
                idx = (idx - 1 + CAP) % CAP;
            }
            int start = (newest - n + 1 + CAP) % CAP;
            for (int i = 0; i < n; i++) {
                int k = (start + i) % CAP;
                st[i] = tBuf[k];
                sx[i] = xBuf[k];
                sy[i] = yBuf[k];
                sz[i] = zBuf[k];
            }
            return n;
        }
    }

    private void analyze() {
        int n = snapshot();
        Analysis.Spectrum sp = Analysis.welch(st, sx, sy, sz, n);
        if (sp == null) { refresh(); return; }
        lastSpectrum = sp;
        lastPeaks = Analysis.peaks(sp, 4, Math.min(sp.fs * 0.45, 200), 3);
        refresh();
    }

    // ---------------------------------------------------------------- results

    private void refresh() {
        double rpm = num(rpmIn), kmh = num(kmhIn), fd = num(fdIn);
        List<Orders.Source> src = Orders.compute(rpm, kmh, tyreIn.getText().toString(), fd);
        double peakHz = lastPeaks.isEmpty() ? Double.NaN : lastPeaks.get(0).hz;
        spectrumView.setData(lastSpectrum, src, peakHz);

        // Status line.
        StringBuilder sb = new StringBuilder();
        if (!running) sb.append(lastSpectrum == null ? "Stopped" : "Stopped · last reading kept");
        else if (lastSpectrum == null) sb.append("Collecting the first few seconds…");
        else sb.append(String.format(Locale.US, "Measuring · %.0f samples/s · %.0f s window", lastSpectrum.fs, WINDOW_S));
        if (frozen) sb.append(" · frozen");
        if (lastSpectrum != null) {
            double needed = 0;
            for (Orders.Source s : src) if (s.family == Orders.Family.ENGINE && s.shortName.equals("2E")) needed = s.hz;
            if (needed > 0 && lastSpectrum.fs < needed * 2.2) {
                sb.append(String.format(Locale.US,
                        "\nWarning: %.0f samples/s is too slow to see %.0f Hz. Results above %.0f Hz are not reliable.",
                        lastSpectrum.fs, needed, lastSpectrum.fs / 2.2));
            }
        }
        status.setText(sb.toString());

        // Verdict.
        if (lastPeaks.isEmpty()) {
            chip.setText(running ? "LISTENING" : "NO READING YET");
            chip.setTextColor(Theme.MUTED);
            resultTitle.setText(running ? "Hold the engine steady" : "Press Start to measure");
            resultWhat.setText("Lay the phone flat on the seat rail or centre console. Hold the rpm you typed below, under load if that's when it shakes.");
            resultCheck.setText("");
            peaksText.setText("");
            return;
        }
        Analysis.Peak top = lastPeaks.get(0);
        Orders.Source best = Orders.bestMatch(src, top.hz, MATCH_TOL);
        if (best != null) {
            chip.setText(String.format(Locale.US, "MATCH · %s", pct(best.diff(top.hz))));
            chip.setTextColor(Theme.OK);
            resultTitle.setText(best.name);
            resultWhat.setText(best.what);
            resultCheck.setText("Check: " + best.check);
        } else {
            Orders.Source near = Orders.nearest(src, top.hz);
            chip.setText("NO CLEAN MATCH");
            chip.setTextColor(Theme.WARN);
            resultTitle.setText(String.format(Locale.US, "%.1f Hz doesn't line up with a turning part", top.hz));
            resultWhat.setText(near == null ? "Type the engine speed so the app can compare."
                    : String.format(Locale.US, "Closest is %s at %.1f Hz (%s). A peak that stays put when rpm and speed change is usually a bracket, pipe or panel resonating on its own.",
                    near.name, near.hz, pct(near.diff(top.hz))));
            resultCheck.setText("Make sure the rpm you typed is the rpm the engine is actually holding.");
        }

        StringBuilder ps = new StringBuilder("Strongest peaks\n");
        for (Analysis.Peak p : lastPeaks) {
            Orders.Source m = Orders.bestMatch(src, p.hz, MATCH_TOL);
            ps.append(String.format(Locale.US, "%6.1f Hz  %5.1f mg  %s\n", p.hz, p.amp / G * 1000,
                    m == null ? "–" : m.name + " (" + pct(m.diff(p.hz)) + ")"));
        }
        peaksText.setText(ps.toString().trim());
    }

    private static String pct(double d) {
        return String.format(Locale.US, "%+.1f%%", d * 100);
    }

    private static double num(EditText e) {
        try {
            return Double.parseDouble(e.getText().toString().trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- inputs

    private void loadInputs() {
        loading = true;
        rpmIn.setText(prefs.getString("rpm", "1400"));
        kmhIn.setText(prefs.getString("kmh", "0"));
        tyreIn.setText(prefs.getString("tyre", "225/55R18"));
        fdIn.setText(prefs.getString("fd", "3.700"));
        loading = false;
    }

    private void saveInputs() {
        prefs.edit()
                .putString("rpm", rpmIn.getText().toString())
                .putString("kmh", kmhIn.getText().toString())
                .putString("tyre", tyreIn.getText().toString())
                .putString("fd", fdIn.getText().toString())
                .apply();
    }

    private final TextWatcher watcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {
            if (loading) return;
            saveInputs();
            refresh();
        }
    };

    private void bumpRpm(int delta) {
        double v = Math.max(0, num(rpmIn) + delta);
        rpmIn.setText(String.format(Locale.US, "%.0f", v));
    }

    private void updateButtons() {
        startBtn.setText(running ? "Stop" : "Start");
        startBtn.setBackgroundTintList(ColorStateList.valueOf(running ? Theme.LINE : Theme.ENGINE));
        startBtn.setTextColor(running ? Theme.INK : Theme.BG);
        freezeBtn.setEnabled(running);
        freezeBtn.setAlpha(running ? 1f : 0.4f);
        freezeBtn.setText(frozen ? "Resume" : "Freeze");
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.SURFACE);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Theme.LINE);
        l.setBackground(bg);
        return l;
    }

    private LinearLayout.LayoutParams gapBelow(int dpGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(dpGap);
        return lp;
    }

    private EditText field(int inputType) {
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setTextColor(Theme.INK);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setTypeface(Typeface.MONOSPACE);
        e.setSingleLine(true);
        e.setBackgroundTintList(ColorStateList.valueOf(Theme.MUTED));
        e.addTextChangedListener(watcher);
        return e;
    }

    private View labelled(String label, View input, String unit) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(13, Theme.MUTED, false);
        l.setText(label);
        col.addView(l);
        col.addView(input);
        if (unit != null) {
            TextView u = text(12, Theme.MUTED, false);
            u.setText(unit);
            col.addView(u);
        }
        return col;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTextColor(Theme.INK);
        b.setBackgroundTintList(ColorStateList.valueOf(Theme.LINE));
        return b;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(root);

        TextView title = text(26, Theme.INK, true);
        title.setText("Shake Finder");
        root.addView(title, gapBelow(2));
        TextView sub = text(14, Theme.MUTED, false);
        sub.setText("Measures the shake, then names the part that turns at that frequency.");
        root.addView(sub, gapBelow(14));

        // Spectrum card.
        LinearLayout specCard = card();
        status = text(12, Theme.MUTED, false);
        status.setTypeface(Typeface.MONOSPACE);
        specCard.addView(status, gapBelow(8));
        spectrumView = new SpectrumView(this);
        specCard.addView(spectrumView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setPadding(0, dp(8), 0, 0);
        String[] names = {"E engine", "P propshaft", "T tyre", "▲ your peak"};
        int[] cols = {Theme.ENGINE, Theme.PROPSHAFT, Theme.TYRE, Theme.INK};
        for (int i = 0; i < names.length; i++) {
            TextView t = text(12, cols[i], true);
            t.setText(names[i]);
            t.setPadding(0, 0, dp(14), 0);
            legend.addView(t);
        }
        specCard.addView(legend);
        root.addView(specCard, gapBelow(12));

        // Buttons.
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = button("Start");
        freezeBtn = button("Freeze");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(52), 1f);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(52), 1f);
        half2.leftMargin = dp(10);
        btnRow.addView(startBtn, half);
        btnRow.addView(freezeBtn, half2);
        startBtn.setOnClickListener(v -> { if (running) stopMeasuring(); else startMeasuring(); });
        freezeBtn.setOnClickListener(v -> { frozen = !frozen; updateButtons(); refresh(); });
        root.addView(btnRow, gapBelow(12));

        // Verdict card.
        LinearLayout res = card();
        chip = text(12, Theme.MUTED, true);
        chip.setLetterSpacing(0.08f);
        res.addView(chip, gapBelow(4));
        resultTitle = text(22, Theme.INK, true);
        res.addView(resultTitle, gapBelow(6));
        resultWhat = text(15, Theme.INK, false);
        res.addView(resultWhat, gapBelow(6));
        resultCheck = text(14, Theme.MUTED, false);
        res.addView(resultCheck, gapBelow(10));
        peaksText = text(13, Theme.MUTED, false);
        peaksText.setTypeface(Typeface.MONOSPACE);
        res.addView(peaksText);
        root.addView(res, gapBelow(12));

        // Inputs card.
        LinearLayout in = card();
        TextView inTitle = text(13, Theme.MUTED, true);
        inTitle.setText("WHAT THE CAR IS DOING");
        inTitle.setLetterSpacing(0.08f);
        in.addView(inTitle, gapBelow(8));

        rpmIn = field(InputType.TYPE_CLASS_NUMBER);
        rpmIn.setGravity(Gravity.CENTER);
        LinearLayout rpmRow = new LinearLayout(this);
        rpmRow.setOrientation(LinearLayout.HORIZONTAL);
        rpmRow.setGravity(Gravity.CENTER_VERTICAL);
        Button minus = button("−100");
        Button plus = button("+100");
        minus.setOnClickListener(v -> bumpRpm(-100));
        plus.setOnClickListener(v -> bumpRpm(100));
        rpmRow.addView(minus, new LinearLayout.LayoutParams(dp(84), dp(48)));
        rpmRow.addView(rpmIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rpmRow.addView(plus, new LinearLayout.LayoutParams(dp(84), dp(48)));
        in.addView(labelled("Engine speed (rpm)", rpmRow, "Engine lines move with this number"), gapBelow(12));

        kmhIn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.addView(labelled("Road speed (km/h)", kmhIn, "0 when parked in D or R"), gapBelow(12));

        tyreIn = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        in.addView(labelled("Tyre size", tyreIn, "Printed on the tyre sidewall, e.g. 225/55R18"), gapBelow(12));

        fdIn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.addView(labelled("Final drive ratio", fdIn, "Confirm from the service manual or ID plate"));
        root.addView(in, gapBelow(12));

        TextView tip = text(13, Theme.MUTED, false);
        tip.setText("Tip: if two parts match at once, keep the same rpm at a different road speed. Engine lines stay put; tyre and propshaft lines move.");
        root.addView(tip);

        updateButtons();
        return scroll;
    }
}
