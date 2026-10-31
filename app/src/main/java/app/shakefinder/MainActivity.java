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
import android.widget.CheckBox;
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
    private boolean running = false, frozen = false, tapMode = false;
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
    private TextView status, chip, resultTitle, resultWhat, resultCheck, peaksText, rpmLine, intro;
    private LinearLayout altRow, inputsCard, legend;
    private EditText rpmIn, kmhIn, tyreIn, fdIn;
    private CheckBox autoBox;
    private Button startBtn, freezeBtn, engineModeBtn, tapModeBtn;
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
        applyMode();
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
        frozen = false;
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
        lastPeaks = Analysis.peaks(sp, 4, Math.min(sp.fs * 0.45, 200), 12);
        refresh();
    }

    // ---------------------------------------------------------------- results

    private void refresh() {
        double typed = num(rpmIn), kmh = num(kmhIn), fd = num(fdIn);
        double df = lastSpectrum != null ? lastSpectrum.df : 0.4;

        AutoRpm.Result auto = null;
        if (!tapMode && autoBox.isChecked() && !lastPeaks.isEmpty()) {
            auto = AutoRpm.estimate(lastPeaks, typed, df);
        }
        double rpm = auto != null ? auto.rpm : typed;
        List<Orders.Source> src = tapMode ? new ArrayList<>()
                : Orders.compute(rpm, kmh, tyreIn.getText().toString(), fd);
        double peakHz = lastPeaks.isEmpty() ? Double.NaN : lastPeaks.get(0).hz;
        spectrumView.setData(lastSpectrum, src, peakHz);

        updateStatus(rpm);
        updateRpmLine(auto, typed);

        if (lastPeaks.isEmpty()) {
            showWaiting();
            return;
        }
        if (tapMode) showTapResult();
        else showEngineResult(src, rpm);
    }

    private void updateStatus(double rpm) {
        StringBuilder sb = new StringBuilder();
        if (!running) sb.append(lastSpectrum == null ? "Stopped" : "Stopped · last reading kept");
        else if (lastSpectrum == null) sb.append("Collecting the first few seconds…");
        else sb.append(String.format(Locale.US, "Measuring · %.0f samples/s · %.0f s window", lastSpectrum.fs, WINDOW_S));
        if (frozen) sb.append(" · frozen");
        if (lastSpectrum != null && !tapMode && rpm > 0) {
            double secondOrder = rpm / 30.0;
            if (lastSpectrum.fs < secondOrder * 2.2) {
                sb.append(String.format(Locale.US,
                        "\nWarning: %.0f samples/s is too slow to see %.0f Hz. Results above %.0f Hz are not reliable.",
                        lastSpectrum.fs, secondOrder, lastSpectrum.fs / 2.2));
            }
        }
        status.setText(sb.toString());
    }

    private void updateRpmLine(AutoRpm.Result auto, double typed) {
        altRow.removeAllViews();
        if (tapMode) {
            rpmLine.setVisibility(View.GONE);
            altRow.setVisibility(View.GONE);
            return;
        }
        rpmLine.setVisibility(View.VISIBLE);
        if (auto != null) {
            rpmLine.setText(String.format(Locale.US,
                    "Engine speed measured from the peaks: %.0f rpm (you typed %.0f)", auto.rpm, typed));
            rpmLine.setTextColor(Theme.OK);
            for (final double alt : auto.others) {
                Button b = button(String.format(Locale.US, "Tach showed about %.0f?", alt));
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                b.setOnClickListener(v -> rpmIn.setText(String.format(Locale.US, "%.0f", alt)));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
                if (altRow.getChildCount() > 0) lp.leftMargin = dp(8);
                altRow.addView(b, lp);
            }
            altRow.setVisibility(altRow.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        } else if (autoBox.isChecked() && !lastPeaks.isEmpty()) {
            rpmLine.setText(String.format(Locale.US,
                    "Couldn't read engine speed from the peaks. Using the %.0f rpm you typed.", typed));
            rpmLine.setTextColor(Theme.WARN);
            altRow.setVisibility(View.GONE);
        } else if (!autoBox.isChecked()) {
            rpmLine.setText(String.format(Locale.US, "Using the %.0f rpm you typed.", typed));
            rpmLine.setTextColor(Theme.MUTED);
            altRow.setVisibility(View.GONE);
        } else {
            rpmLine.setText("Engine speed will be measured from the peaks.");
            rpmLine.setTextColor(Theme.MUTED);
            altRow.setVisibility(View.GONE);
        }
    }

    private void showWaiting() {
        chip.setTextColor(Theme.MUTED);
        resultCheck.setText("");
        peaksText.setText("");
        if (tapMode) {
            chip.setText(running ? "LISTENING" : "TAP TEST");
            resultTitle.setText(running ? "Tap the part now" : "Press Start, then tap the part");
            resultWhat.setText("Engine off and exhaust cold. Tape the phone firmly to the part, press Start, tap the part sharply 3–4 times, then press Freeze.");
        } else {
            chip.setText(running ? "LISTENING" : "NO READING YET");
            resultTitle.setText(running ? "Hold the engine steady" : "Press Start to measure");
            resultWhat.setText("Lay the phone flat on the seat rail or centre console. Hold the rpm, under load if that's when it shakes.");
        }
    }

    private void showEngineResult(List<Orders.Source> src, double rpm) {
        Analysis.Peak top = lastPeaks.get(0);
        Orders.Source best = Orders.bestMatch(src, top.hz, MATCH_TOL);
        if (best != null) {
            chip.setText(String.format(Locale.US, "MATCH · %s", pct(best.diff(top.hz))));
            chip.setTextColor(Theme.OK);
            resultTitle.setText(String.format(Locale.US, "%s · %.1f Hz", best.name, top.hz));
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
            resultCheck.setText("Use Tap test mode to find which part rings at this frequency.");
        }

        StringBuilder ps = new StringBuilder("Strongest peaks\n");
        int shown = 0;
        for (Analysis.Peak p : lastPeaks) {
            if (shown >= 5) break;
            Orders.Source m = Orders.bestMatch(src, p.hz, MATCH_TOL);
            ps.append(String.format(Locale.US, "%6.1f Hz %6.1f mg  %s\n", p.hz, p.amp / G * 1000,
                    m == null ? "–" : m.name.replace("Engine ", "").replace(" order", "") + (m.family == Orders.Family.ENGINE ? " engine" : "")));
            shown++;
        }
        peaksText.setText(ps.toString().trim());
    }

    private void showTapResult() {
        Analysis.Peak top = lastPeaks.get(0);
        chip.setText("TAP TEST");
        chip.setTextColor(Theme.OK);
        resultTitle.setText(String.format(Locale.US, "This part rings at %.1f Hz", top.hz));
        resultWhat.setText(String.format(Locale.US,
                "The engine's 2nd order reaches %.1f Hz at %.0f rpm, and its 4th order at %.0f rpm. "
                        + "If either is where you feel the shake, this part is a prime suspect.",
                top.hz, top.hz * 30, top.hz * 15));
        resultCheck.setText("Test each suspect part the same way and compare. Only the part ringing near your shake frequency matters.");

        StringBuilder ps = new StringBuilder("Strongest peaks\n");
        int shown = 0;
        for (Analysis.Peak p : lastPeaks) {
            if (shown >= 4) break;
            ps.append(String.format(Locale.US, "%6.1f Hz  2nd order at %4.0f rpm\n", p.hz, p.hz * 30));
            shown++;
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
        autoBox.setChecked(prefs.getBoolean("auto", true));
        tapMode = prefs.getBoolean("tap", false);
        loading = false;
    }

    private void saveInputs() {
        prefs.edit()
                .putString("rpm", rpmIn.getText().toString())
                .putString("kmh", kmhIn.getText().toString())
                .putString("tyre", tyreIn.getText().toString())
                .putString("fd", fdIn.getText().toString())
                .putBoolean("auto", autoBox.isChecked())
                .putBoolean("tap", tapMode)
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

    private void setMode(boolean tap) {
        if (tapMode == tap) return;
        tapMode = tap;
        saveInputs();
        applyMode();
        refresh();
    }

    private void applyMode() {
        engineModeBtn.setBackgroundTintList(ColorStateList.valueOf(tapMode ? Theme.SURFACE : Theme.LINE));
        engineModeBtn.setTextColor(tapMode ? Theme.MUTED : Theme.INK);
        tapModeBtn.setBackgroundTintList(ColorStateList.valueOf(tapMode ? Theme.LINE : Theme.SURFACE));
        tapModeBtn.setTextColor(tapMode ? Theme.INK : Theme.MUTED);
        inputsCard.setVisibility(tapMode ? View.GONE : View.VISIBLE);
        legend.setVisibility(tapMode ? View.GONE : View.VISIBLE);
        intro.setText(tapMode
                ? "Tap test: finds the frequency a part rings at. Engine off."
                : "Measures the shake, then names the part that turns at that frequency.");
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
        intro = text(14, Theme.MUTED, false);
        root.addView(intro, gapBelow(12));

        // Mode switch.
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        engineModeBtn = button("Engine running");
        tapModeBtn = button("Tap test");
        engineModeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tapModeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(0, dp(44), 1f);
        m2.leftMargin = dp(8);
        modeRow.addView(engineModeBtn, m1);
        modeRow.addView(tapModeBtn, m2);
        engineModeBtn.setOnClickListener(v -> setMode(false));
        tapModeBtn.setOnClickListener(v -> setMode(true));
        root.addView(modeRow, gapBelow(12));

        // Spectrum card.
        LinearLayout specCard = card();
        status = text(12, Theme.MUTED, false);
        status.setTypeface(Typeface.MONOSPACE);
        specCard.addView(status, gapBelow(8));
        spectrumView = new SpectrumView(this);
        specCard.addView(spectrumView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));
        legend = new LinearLayout(this);
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
        rpmLine = text(13, Theme.OK, false);
        res.addView(rpmLine, gapBelow(6));
        altRow = new LinearLayout(this);
        altRow.setOrientation(LinearLayout.HORIZONTAL);
        res.addView(altRow, gapBelow(8));
        resultWhat = text(15, Theme.INK, false);
        res.addView(resultWhat, gapBelow(6));
        resultCheck = text(14, Theme.MUTED, false);
        res.addView(resultCheck, gapBelow(10));
        peaksText = text(13, Theme.MUTED, false);
        peaksText.setTypeface(Typeface.MONOSPACE);
        res.addView(peaksText);
        root.addView(res, gapBelow(12));

        // Inputs card.
        inputsCard = card();
        TextView inTitle = text(13, Theme.MUTED, true);
        inTitle.setText("WHAT THE CAR IS DOING");
        inTitle.setLetterSpacing(0.08f);
        inputsCard.addView(inTitle, gapBelow(8));

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
        inputsCard.addView(labelled("Rough engine speed from the tach (rpm)", rpmRow,
                "Within about 30% is enough. The app measures the exact value."), gapBelow(8));

        autoBox = new CheckBox(this);
        autoBox.setText("Measure exact rpm from the peaks");
        autoBox.setTextColor(Theme.INK);
        autoBox.setButtonTintList(ColorStateList.valueOf(Theme.ENGINE));
        autoBox.setOnCheckedChangeListener((b, checked) -> { if (!loading) { saveInputs(); refresh(); } });
        inputsCard.addView(autoBox, gapBelow(12));

        kmhIn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputsCard.addView(labelled("Road speed (km/h)", kmhIn, "0 when parked in P, D or R"), gapBelow(12));

        tyreIn = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        inputsCard.addView(labelled("Tyre size", tyreIn, "Printed on the tyre sidewall, e.g. 225/55R18"), gapBelow(12));

        fdIn = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputsCard.addView(labelled("Final drive ratio", fdIn, "Confirm from the service manual or ID plate"));
        root.addView(inputsCard, gapBelow(12));

        TextView tip = text(13, Theme.MUTED, false);
        tip.setText("Tip: if two parts match at once, keep the same rpm at a different road speed. Engine lines stay put; tyre and propshaft lines move.");
        root.addView(tip);

        updateButtons();
        return scroll;
    }
}
