package app.shakefinder;

import android.Manifest;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity
        implements SensorEventListener, LocationListener, ObdLink.Listener, ObdPage.Host, SavedPage.Host {

    private static final int CAP = 16384;            // ring buffer: ~30 s at 500 Hz
    private static final double SLOW_WIN = 5.2;      // seconds behind the chart and the verdict
    private static final double FAST_WIN = 2.0;      // seconds behind the rpm reading
    private static final double SLOW_SEG = 2.5, FAST_SEG = 1.1;
    private static final double TAP_WIN = 4.0;
    private static final long UPDATE_MS = 250;
    private static final double G = 9.80665;
    private static final int PAGE_SHAKE = 0, PAGE_TAP = 1, PAGE_OBD = 2, PAGE_SAVED = 3;
    private static final double MAX_RECORD_S = 1800; // a forgotten recording stops itself after half an hour
    private static final int[] SWEEP_ORDER = {SweepMap.PEAK, 2, 3, 1, 0}; // All, 2E, 4E, 1E, ½E
    private static final int REQ_LOCATION = 1, REQ_MIC = 2, REQ_BT = 3;

    private enum RpmSrc { NONE, VIB, VIB_SOUND, SOUND, LOCKED, OBD, ENGINE_OFF, TYPED }
    private enum SpeedSrc { NONE, GPS, OBD, TYPED }

    // Sensor ring buffer (written on the sensor thread, read on the UI thread).
    private final Object lock = new Object();
    private final long[] tBuf = new long[CAP];
    private final float[] xBuf = new float[CAP], yBuf = new float[CAP], zBuf = new float[CAP];
    private int head = 0, count = 0;
    private final long[] st = new long[CAP];
    private final float[] sx = new float[CAP], sy = new float[CAP], sz = new float[CAP];

    private SensorManager sensorManager;
    private Sensor accel;
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private LocationManager locationManager;
    private boolean gpsListening = false;
    private int satsUsed = -1, satsSeen = -1;
    private GnssStatus.Callback gnssCallback;

    private final SpeedFilter gpsSpeed = new SpeedFilter();
    private final TimeSeries obdRpm = new TimeSeries(128), obdSpeed = new TimeSeries(64), obdLoad = new TimeSeries(16);
    private final RpmTracker vibTracker = new RpmTracker(RpmTracker.Kind.VIBRATION);
    private final RpmTracker soundTracker = new RpmTracker(RpmTracker.Kind.SOUND);
    private final SoundCapture mic = new SoundCapture();
    private ObdLink link;
    private BluetoothAdapter btAdapter;
    private int pendingBtAction = 0; // 1 = choose, 2 = connect

    // What the last analysis found.
    private boolean running = false, frozen = false;
    private Analysis.Spectrum slow;
    private List<Analysis.Peak> slowPeaks = new ArrayList<>();
    private SoundRpm.Sound sound;
    private List<Analysis.Peak> soundPeaks = new ArrayList<>();
    private double rpmValue = Double.NaN, rpmGauge = Double.NaN, rpmConf = 0, rpmGoodT = -1e9;
    private RpmSrc rpmSrc = RpmSrc.NONE;
    private boolean rpmHolding = false, rpmMoving = false, rpmFallback = false;
    private RpmTracker.Estimate lastEst;
    private double kmhValue = Double.NaN;
    private SpeedSrc speedSrc = SpeedSrc.NONE;
    private double selectedHz = Double.NaN;
    private String chipSignature = "";
    private String verdictSignature = "";

    // Recording a run.
    private final Recorder recorder = new Recorder();
    private String recBase;
    private long recTimeMs;
    private List<Integer> obdPids = new ArrayList<>();
    private int[] recPids = new int[0];
    private final HashMap<Integer, double[]> obdLatest = new HashMap<>(); // pid -> {value, time}
    private int sweepSeries = SweepMap.PEAK;

    // What is on screen, kept so "Save this reading" can store exactly that.
    private List<Orders.Source> lastSrc = new ArrayList<>();
    private List<Analysis.Peak> lastPeaks = new ArrayList<>();
    private boolean lastSoundView = false;
    private double lastFTop = 100;
    private String lastChip = "", lastTitle = "", lastWhat = "", lastCheck = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (running && !frozen) analyze();
            else liveSources();
            handler.postDelayed(this, running ? UPDATE_MS : 500);
        }
    };

    // Views.
    private SharedPreferences prefs;
    private int page = PAGE_SHAKE;
    private int measurePage = PAGE_SHAKE; // which of Engine / Tap test the current data belongs to
    private final android.graphics.drawable.GradientDrawable liveDotBg = new android.graphics.drawable.GradientDrawable();
    private boolean showSound = false, loading = false;
    private ScrollView scroll;
    private LinearLayout shakePage, gaugeRow, chipRow, setupBody, legend, inputsCard, peaksCard, sweepCard;
    private ObdPage obdPage;
    private SavedPage savedPage;
    private SweepView sweepView;
    private TextView recBtn, saveBtn, sweepText;
    private ObjectAnimator recPulse;
    private SegmentView pageSwitch, sourceSwitch;
    private GaugeView rpmGaugeView, speedGaugeView;
    private SpectrumView spectrumView;
    private PeakListView peakList;
    private LevelMeterView levelMeter;
    private TextView intro, status, chip, resultTitle, resultWhat, resultCheck, resultNote, startBtn, freezeBtn, setupHeader, peaksCaption;
    private View liveDot;
    private ObjectAnimator dotPulse, startPulse;
    private EditText rpmIn, kmhIn, tyreIn, fdIn;
    private Switch autoBox, gpsBox, micBox, obdBox;
    private View kmhRow;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("shake", MODE_PRIVATE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;
        if (btAdapter != null) link = new ObdLink(btAdapter, this);
        sensorThread = new HandlerThread("accel");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.BG);
        setContentView(buildUi());
        loadInputs();
        applyPage(false);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startGpsIfWanted(true);
        if (prefs.getBoolean("obd", false)) connectObd(false);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopMeasuring();
        stopGps();
        if (link != null) link.stop();
        if (savedPage != null) savedPage.stopAudio();
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        recorder.stop();
        mic.stop();
        if (savedPage != null) savedPage.stopAudio();
        if (obdPage != null) obdPage.stop();
        if (sensorThread != null) sensorThread.quitSafely();
    }

    private static double nowSec() {
        return SystemClock.elapsedRealtimeNanos() / 1e9;
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- accelerometer

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
        slow = null;
        slowPeaks = new ArrayList<>();
        sound = null;
        soundPeaks = new ArrayList<>();
        lastEst = null;
        selectedHz = Double.NaN;
        vibTracker.reset();
        soundTracker.reset();
        if (rpmSrc != RpmSrc.OBD) { rpmValue = Double.NaN; rpmGauge = Double.NaN; rpmSrc = RpmSrc.NONE; }
        frozen = false;
        sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
        running = true;
        if (measurePage == PAGE_SHAKE && micBox.isChecked() && granted(Manifest.permission.RECORD_AUDIO)) mic.start(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, UPDATE_MS);
        updateButtons();
        refresh();
    }

    private void stopMeasuring() {
        if (!running) return;
        if (recorder.isRecording()) stopRecording();
        running = false;
        frozen = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        mic.stop();
        if (page != PAGE_OBD) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateButtons();
        refresh();
    }

    /** Copies the last windowS seconds of samples into st/sx/sy/sz, oldest first. */
    private int snapshot(double windowS) {
        synchronized (lock) {
            if (count < 2) return 0;
            int newest = (head - 1 + CAP) % CAP;
            long tStart = tBuf[newest] - (long) (windowS * 1e9);
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

    private static double top(Analysis.Spectrum sp) {
        return Math.min(sp.fs * 0.45, 200);
    }

    // ---------------------------------------------------------------- analysis

    private void analyze() {
        double now = nowSec();
        if (measurePage == PAGE_TAP) {
            Analysis.Spectrum sp = Analysis.welch(st, sx, sy, sz, snapshot(TAP_WIN), SLOW_SEG);
            if (sp != null) {
                slow = sp;
                slowPeaks = Analysis.peaks(sp, 4, top(sp), 8, 0.006);
            }
            refresh();
            return;
        }
        Analysis.Spectrum fast = Analysis.welch(st, sx, sy, sz, snapshot(FAST_WIN), FAST_SEG);
        List<Analysis.Peak> fastPeaks = fast == null ? new ArrayList<>() : Analysis.peaks(fast, 4, top(fast), 14, 0.006);
        Analysis.Spectrum sp = Analysis.welch(st, sx, sy, sz, snapshot(SLOW_WIN), SLOW_SEG);
        if (sp != null) {
            slow = sp;
            slowPeaks = Analysis.peaks(sp, 4, top(sp), 14, 0.006);
        }
        SoundRpm dsp = mic.dsp();
        if (mic.isRunning() && dsp != null) {
            sound = dsp.analyze();
            soundPeaks = SoundRpm.peaks(sound, 16);
        } else if (!mic.isRunning()) {
            sound = null;
            soundPeaks = new ArrayList<>();
        }
        updateSpeed(now);
        updateRpm(now, fast, fastPeaks);
        if (recorder.isRecording()) recordTick(now, fast, fastPeaks);
        refresh();
    }

    // ---------------------------------------------------------------- recording

    private void toggleRecording() {
        if (recorder.isRecording()) {
            stopRecording();
            updateButtons();
            refresh();
            return;
        }
        if (measurePage != PAGE_SHAKE) return;
        if (!running) startMeasuring();
        if (!running) return; // no accelerometer
        frozen = false;
        recBase = "run_" + Store.stamp();
        recTimeMs = System.currentTimeMillis();
        File dir = Store.dir(this);
        // One column per OBD value the car reports, if the adapter is live.
        List<String> names = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        if (link != null && link.isLive()) {
            for (int id : obdPids) {
                Elm327.Pid p = Elm327.pid(id);
                if (p == null || id == 0x0C || id == 0x0D) continue;
                ids.add(id);
                names.add("obd_" + p.name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_") + (p.unit.isEmpty() ? "" : "_" + p.unit.replace("°", "deg").replace("λ", "lambda").replace("/", "_per_")));
            }
            link.setLiveAll(true); // log every value, not just the few the shake screen needs
        }
        recPids = new int[ids.size()];
        for (int i = 0; i < recPids.length; i++) recPids[i] = ids.get(i);
        try {
            recorder.start(new File(dir, recBase + ".csv"), names.toArray(new String[0]));
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't start recording: the phone's storage isn't writable.", Toast.LENGTH_LONG).show();
            return;
        }
        if (mic.isRunning()) {
            mic.recordTo(new File(dir, recBase + ".wav"));
        } else {
            Toast.makeText(this, "Recording without sound. To save engine sound too, tap Engine sound above the chart first.", Toast.LENGTH_LONG).show();
        }
        sweepCard.setVisibility(View.VISIBLE);
        updateButtons();
        refresh();
    }

    private void recordTick(double now, Analysis.Spectrum fast, List<Analysis.Peak> fastPeaks) {
        // Engine speed over the same two seconds the strengths are measured over.
        double rpm = rpmSrc == RpmSrc.OBD ? obdRpm.mean(now, FAST_WIN) : rpmValue;
        boolean measured = rpmSrc != RpmSrc.TYPED && rpmSrc != RpmSrc.NONE && rpmSrc != RpmSrc.ENGINE_OFF;
        double[] orderMg = null;
        double tyreMg = Double.NaN, propMg = Double.NaN, peakHz = Double.NaN, peakMg = Double.NaN;
        String match = "";
        if (fast != null) {
            if (!Double.isNaN(rpm) && rpm > 0) {
                orderMg = new double[4];
                for (int i = 0; i < 4; i++) orderMg[i] = Analysis.ampNear(fast, SweepMap.ORDERS[i] * rpm / 60.0, 0.02) / G * 1000;
            }
            double[] road = kmhValue > 0 ? Orders.roadFrequencies(kmhValue, tyreIn.getText().toString(), num(fdIn)) : new double[0];
            if (road.length >= 1) tyreMg = Analysis.ampNear(fast, road[0], 0.035) / G * 1000;
            if (road.length >= 3) propMg = Analysis.ampNear(fast, road[2], 0.035) / G * 1000;
            if (!fastPeaks.isEmpty()) {
                Analysis.Peak top = fastPeaks.get(0);
                peakHz = top.hz;
                peakMg = top.amp / G * 1000;
                Orders.Source m = Orders.bestMatch(lastSrc, top.hz, fast.df);
                match = m == null ? "" : m.shortName;
            }
        }
        double[] obd = new double[recPids.length];
        for (int i = 0; i < obd.length; i++) {
            double[] v = obdLatest.get(recPids[i]);
            obd[i] = v != null && now - v[1] < 5 ? v[0] : Double.NaN;
        }
        try {
            recorder.row(now, rpm, rpmSourceName(), kmhValue, speedSrc.name().toLowerCase(Locale.US),
                    peakHz, peakMg, match, orderMg, tyreMg, propMg,
                    sound != null && mic.isRunning() ? sound.levelDb : Double.NaN, obd,
                    measured && !rpmHolding && orderMg != null);
        } catch (IOException e) {
            stopRecording();
            Toast.makeText(this, "Recording stopped: the phone's storage is full or not writable.", Toast.LENGTH_LONG).show();
            return;
        }
        if (recorder.seconds() > MAX_RECORD_S) stopRecording();
        updateButtons();
    }

    private String rpmSourceName() {
        switch (rpmSrc) {
            case OBD: return "obd";
            case VIB: return "vibration";
            case VIB_SOUND: return "vibration+sound";
            case SOUND: return "sound";
            case LOCKED: return "locked";
            case TYPED: return "typed";
            case ENGINE_OFF: return "engine_off";
            default: return "";
        }
    }

    private void stopRecording() {
        if (!recorder.isRecording()) return;
        recorder.stop();
        File wav = new File(Store.dir(this), recBase + ".wav");
        boolean hadSound = mic.wavSeconds() > 0.5;
        mic.recordTo(null);
        if (link != null) link.setLiveAll(page == PAGE_OBD);
        if (recorder.rows() < 4) { // a mis-tap: nothing worth keeping
            if (recorder.file() != null) recorder.file().delete();
            wav.delete();
            Toast.makeText(this, "Too short to save.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hadSound) wav.delete();
        try {
            JSONObject o = new JSONObject();
            o.put("type", "run").put("time", recTimeMs).put("note", "");
            o.put("seconds", Math.round(recorder.seconds() * 10) / 10.0).put("rows", recorder.rows());
            o.put("obdColumns", recPids.length).put("hasWav", hadSound);
            if (!Double.isNaN(recorder.rpmMin())) o.put("rpmMin", Math.round(recorder.rpmMin())).put("rpmMax", Math.round(recorder.rpmMax()));
            o.put("maxMg", Math.round(recorder.maxMg() * 100) / 100.0);
            if (!Double.isNaN(recorder.maxHz())) o.put("maxHz", Math.round(recorder.maxHz() * 100) / 100.0);
            if (!Double.isNaN(recorder.maxRpm())) o.put("maxRpm", Math.round(recorder.maxRpm()));
            o.put("maxTag", recorder.maxTag());
            if (!recorder.sweep.isEmpty()) {
                JSONObject sw = new JSONObject();
                sw.put("firstBin", recorder.sweep.firstBin()).put("binRpm", SweepMap.BIN).put("lowRpm", SweepMap.LO);
                for (int i = 0; i < SweepMap.SERIES; i++) sw.put(SweepMap.KEYS[i], Store.array(recorder.sweep.slice(i)));
                o.put("sweep", sw);
            }
            Store.write(new File(Store.dir(this), recBase + ".json"), o.toString());
            Toast.makeText(this, "Run saved. Open the Saved tab to see or share it.", Toast.LENGTH_LONG).show();
        } catch (JSONException | IOException e) {
            Toast.makeText(this, "The run's log was written, but its summary couldn't be saved.", Toast.LENGTH_LONG).show();
        }
    }

    /** Asks for a note, then keeps what is on screen right now. */
    private void saveReading() {
        final boolean soundView = lastSoundView;
        if ((soundView && sound == null) || (!soundView && slow == null)) {
            Toast.makeText(this, "Nothing to save yet. Press Start first.", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText note = Ui.field(this, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, "e.g. before new bracket, in R, brake held");
        note.setTypeface(android.graphics.Typeface.DEFAULT);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(note, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Take the snapshot now, so the reading saved is the one that was showing when the button was pressed.
        final JSONObject snap;
        try {
            snap = snapshotReading(soundView);
        } catch (JSONException e) {
            Toast.makeText(this, "That reading couldn't be saved.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Save this reading")
                .setMessage("Add a note so you know later what this was.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        snap.put("note", note.getText().toString().trim());
                        Store.write(new File(Store.dir(this), "reading_" + Store.stamp() + ".json"), snap.toString());
                        Toast.makeText(this, "Saved. Open the Saved tab to see or compare it.", Toast.LENGTH_LONG).show();
                    } catch (JSONException | IOException e) {
                        Toast.makeText(this, "That reading couldn't be saved.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private JSONObject snapshotReading(boolean soundView) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("type", "reading").put("time", System.currentTimeMillis());
        o.put("mode", measurePage == PAGE_TAP ? "tap" : soundView ? "sound" : "engine");
        o.put("chip", lastChip).put("title", lastTitle).put("what", lastWhat).put("check", lastCheck);
        if (!Double.isNaN(rpmValue) && measurePage != PAGE_TAP) o.put("rpm", Math.round(rpmValue)).put("rpmSource", rpmSourceName());
        if (!Double.isNaN(kmhValue)) o.put("kmh", Math.round(kmhValue * 10) / 10.0);
        o.put("tyre", tyreIn.getText().toString()).put("fd", num(fdIn));
        o.put("fTop", lastFTop);
        double df;
        if (soundView) {
            df = sound.df;
            int n = Math.min(sound.amp.length, (int) Math.ceil(SoundRpm.F_HI / df) + 1);
            double[] db = new double[n];
            for (int k = 0; k < n; k++) db[k] = sound.db(k);
            o.put("unit", "dB").put("df", df).put("values", Store.array(db, n, 1));
        } else {
            df = slow.df;
            int n = Math.min(slow.amp.length, (int) Math.ceil(Math.min(200, slow.fs / 2) / df) + 1);
            double[] mg = new double[n];
            for (int k = 0; k < n; k++) mg[k] = slow.amp[k] / G * 1000;
            o.put("unit", "mg").put("df", df).put("values", Store.array(mg, n, 2));
        }
        JSONArray peaks = new JSONArray();
        for (int i = 0; i < Math.min(8, lastPeaks.size()); i++) {
            Analysis.Peak p = lastPeaks.get(i);
            Orders.Source m = measurePage == PAGE_TAP ? null : Orders.bestMatch(lastSrc, p.hz, df);
            JSONObject jp = new JSONObject();
            jp.put("hz", Math.round(p.hz * 100) / 100.0).put("tag", m == null ? "–" : m.shortName);
            if (soundView) {
                double above = 20 * Math.log10(p.amp);
                jp.put("v", Math.round(above * 10) / 10.0).put("y", Math.round(peakDb(p) * 10) / 10.0)
                        .put("text", String.format(Locale.US, "+%.0f dB", above));
            } else {
                double mg = p.amp / G * 1000;
                jp.put("v", Math.round(mg * 100) / 100.0).put("y", Math.round(mg * 100) / 100.0)
                        .put("text", String.format(Locale.US, "%.1f mg", mg));
            }
            peaks.put(jp);
        }
        o.put("peaks", peaks);
        return o;
    }

    @Override
    public void savedShare(File f) {
        try {
            android.net.Uri uri = ShareProvider.uriFor(f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType(ShareProvider.mime(f.getName()));
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "Shake Finder " + f.getName());
            send.setClipData(ClipData.newRawUri(f.getName(), uri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share " + f.getName()));
        } catch (RuntimeException e) {
            Toast.makeText(this, "No app on this phone can take that file.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void savedScrollTop() {
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
    }

    /** Between measurements the gauges still follow GPS and OBD. */
    private void liveSources() {
        double now = nowSec();
        updateSpeed(now);
        if (measurePage != PAGE_TAP && !frozen) {
            double o = obdRpm.latest(now, 1.5);
            if (!Double.isNaN(o)) {
                rpmSrc = o > 300 ? RpmSrc.OBD : RpmSrc.ENGINE_OFF;
                rpmGauge = o > 300 ? o : Double.NaN;
                if (!running) rpmValue = rpmGauge;
            } else if (rpmSrc == RpmSrc.OBD || rpmSrc == RpmSrc.ENGINE_OFF) {
                rpmSrc = RpmSrc.NONE;
                rpmGauge = Double.NaN;
                if (!running) rpmValue = Double.NaN;
            }
        }
        updateGauges(now);
    }

    private void updateSpeed(double now) {
        boolean gpsOn = gpsBox.isChecked() && granted(Manifest.permission.ACCESS_FINE_LOCATION);
        double g = gpsOn ? gpsSpeed.meanKmh(now, SLOW_WIN) : Double.NaN;
        double o = obdSpeed.ageSec(now) < 3 ? obdSpeed.mean(now, SLOW_WIN) : Double.NaN;
        if (!Double.isNaN(g)) {
            kmhValue = g;
            speedSrc = SpeedSrc.GPS;
        } else if (!Double.isNaN(o)) {
            kmhValue = o;
            speedSrc = SpeedSrc.OBD;
        } else if (!gpsBox.isChecked()) {
            kmhValue = num(kmhIn);
            speedSrc = SpeedSrc.TYPED;
        } else {
            kmhValue = Double.NaN;
            speedSrc = SpeedSrc.NONE;
        }
    }

    private void updateRpm(double now, Analysis.Spectrum fast, List<Analysis.Peak> fastPeaks) {
        lastEst = null;
        rpmHolding = false;
        rpmFallback = false;
        rpmMoving = fast != null && !Double.isNaN(fast.drift) && fast.drift > 0.04;
        double df = slow != null ? slow.df : 0.4;

        // 1. The engine computer knows best.
        if (obdRpm.ageSec(now) < 1.5) {
            double mean = obdRpm.mean(now, SLOW_WIN);
            double latest = obdRpm.latest(now, 1.5);
            if (latest > 300) {
                rpmValue = slow != null ? RpmTracker.refine(mean, slowPeaks, df) : mean;
                rpmGauge = latest;
                rpmSrc = RpmSrc.OBD;
                rpmConf = 1;
                rpmGoodT = now;
                rpmMoving = obdRpm.spread(now, SLOW_WIN) > 0.04 * mean;
            } else {
                rpmValue = Double.NaN;
                rpmGauge = Double.NaN;
                rpmSrc = RpmSrc.ENGINE_OFF;
            }
            return;
        }

        // 2. Typed only.
        if (!autoBox.isChecked()) {
            double t = num(rpmIn);
            rpmValue = t > 0 ? t : Double.NaN;
            rpmGauge = rpmValue;
            rpmSrc = t > 0 ? RpmSrc.TYPED : RpmSrc.NONE;
            return;
        }

        // 3. Measure it: engine sound and vibration, each backing the other up.
        double[] exclude = kmhValue > 0
                ? Orders.roadFrequencies(kmhValue, tyreIn.getText().toString(), num(fdIn)) : null;
        double exTol = speedSrc == SpeedSrc.GPS ? 0.035 : 0.07;
        RpmTracker.Estimate se = null;
        if (sound != null && !soundPeaks.isEmpty()) {
            se = soundTracker.update(soundPeaks, sound.df, null, 0, now, null);
        }
        RpmTracker.Estimate ve = null;
        if (fast != null && !rpmMoving) {
            double[][] support = null;
            if (se != null) {
                support = new double[1 + se.alternatives.size()][];
                support[0] = new double[]{se.rpm, Math.max(0.3, se.confidence)};
                for (int i = 0; i < se.alternatives.size(); i++) support[i + 1] = new double[]{se.alternatives.get(i), 0.2};
            }
            ve = vibTracker.update(fastPeaks, fast.df, exclude, exTol, now, support);
        }
        boolean steady = slow != null && (Double.isNaN(slow.drift) || slow.drift < 0.02);
        if (ve != null) {
            rpmValue = steady ? RpmTracker.refine(ve.rpm, slowPeaks, df) : ve.rpm;
            boolean agree = se != null && Math.abs(Math.log(se.rpm / ve.rpm)) < 0.025;
            rpmSrc = ve.locked ? RpmSrc.LOCKED : agree ? RpmSrc.VIB_SOUND : RpmSrc.VIB;
            rpmConf = agree ? Math.max(ve.confidence, 0.9) : ve.confidence;
            lastEst = agree && !ve.locked ? null : ve; // agreement settles it: no "other reading" chips
            rpmGoodT = now;
        } else if (se != null && se.confidence >= 0.5 && se.matched >= 3 && !rpmMoving) {
            rpmValue = steady ? RpmTracker.refine(se.rpm, slowPeaks, df) : se.rpm;
            rpmSrc = se.locked ? RpmSrc.LOCKED : RpmSrc.SOUND;
            rpmConf = se.confidence;
            lastEst = se;
            rpmGoodT = now;
        } else if (!Double.isNaN(rpmValue) && now - rpmGoodT < 1.5
                && rpmSrc != RpmSrc.TYPED && rpmSrc != RpmSrc.NONE && rpmSrc != RpmSrc.ENGINE_OFF) {
            rpmHolding = true; // ride through a short gap
        } else {
            double t = num(rpmIn);
            rpmValue = t > 0 ? t : Double.NaN;
            rpmSrc = t > 0 ? RpmSrc.TYPED : RpmSrc.NONE;
            rpmFallback = t > 0;
        }
        rpmGauge = rpmValue;
    }

    // ---------------------------------------------------------------- results

    private void refresh() {
        if (spectrumView == null) return;
        double now = nowSec();
        boolean tap = measurePage == PAGE_TAP;
        boolean soundView = !tap && showSound && micBox.isChecked();
        double df = slow != null ? slow.df : 0.4;

        boolean rpmMeasured = rpmSrc != RpmSrc.TYPED && rpmSrc != RpmSrc.NONE;
        double engTol = rpmMeasured ? 0.015 : 0.06;
        double roadTol = speedSrc == SpeedSrc.GPS ? 0.035 : speedSrc == SpeedSrc.OBD ? 0.05 : 0.07;
        List<Orders.Source> src = tap ? new ArrayList<>()
                : Orders.compute(Double.isNaN(rpmValue) ? 0 : rpmValue, Double.isNaN(kmhValue) ? 0 : kmhValue,
                tyreIn.getText().toString(), num(fdIn), engTol, roadTol);

        // The peaks on show, and the one the verdict talks about.
        List<Analysis.Peak> peaks = soundView ? soundPeaks : slowPeaks;
        double plotDf = soundView && sound != null ? sound.df : df;
        Analysis.Peak subject = null;
        if (!peaks.isEmpty()) {
            subject = peaks.get(0);
            if (!Double.isNaN(selectedHz)) {
                double best = Math.max(1.0, selectedHz * 0.025);
                for (Analysis.Peak p : peaks) {
                    if (Math.abs(p.hz - selectedHz) < best) { best = Math.abs(p.hz - selectedHz); subject = p; }
                }
            }
        }

        lastSrc = src;
        lastPeaks = peaks;
        lastSoundView = soundView;
        drawChart(soundView, src, peaks, subject, plotDf);
        updateSweep();
        updateStatus();
        updateGauges(now);
        updateChips(now);

        if (subject == null) showWaiting(tap, soundView);
        else if (tap) showTapResult(subject);
        else if (soundView) showSoundResult(src, subject);
        else showEngineResult(src, subject, df);
        updatePeakList(soundView, tap, src, peaks, subject, plotDf);
    }

    private void updateSweep() {
        boolean show = measurePage == PAGE_SHAKE && (recorder.isRecording() || !recorder.sweep.isEmpty());
        sweepCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        sweepView.setData(recorder.sweep, sweepSeries, running ? rpmValue : Double.NaN, recorder.isRecording());
        String t = recorder.sweep.worstText(sweepSeries);
        if (t.isEmpty()) {
            t = !recorder.isRecording() ? "Nothing at this order during the run."
                    : Double.isNaN(rpmValue) ? "Waiting for an engine speed reading…"
                    : "Recording. Rev slowly through the range where it shakes.";
        }
        sweepText.setText(t);
    }

    private void drawChart(boolean soundView, List<Orders.Source> src, List<Analysis.Peak> peaks,
                           Analysis.Peak subject, double plotDf) {
        List<SpectrumView.Mark> marks = new ArrayList<>();
        double selHz = subject == null ? Double.NaN : subject.hz;
        boolean live = running && !frozen;
        if (soundView) {
            if (sound == null) {
                String hint = !granted(Manifest.permission.RECORD_AUDIO) ? "Microphone permission is needed"
                        : running ? "Listening…" : "Press Start to hear the engine";
                spectrumView.setPlot(0, null, 0, true, "dB", Theme.SPEED, 400, src, marks, Double.NaN, live, hint);
                return;
            }
            int n = Math.min(sound.amp.length, (int) Math.ceil(SoundRpm.F_HI / sound.df) + 1);
            double[] db = new double[n];
            for (int k = 0; k < n; k++) db[k] = sound.db(k);
            for (int i = 0; i < Math.min(8, peaks.size()); i++) {
                Analysis.Peak p = peaks.get(i);
                Orders.Source m = Orders.bestMatch(src, p.hz, plotDf);
                marks.add(new SpectrumView.Mark(p.hz, peakDb(p), m == null ? Theme.INK : Theme.familyColor(m.family)));
            }
            double fTop = Double.isNaN(rpmValue) ? 400 : Math.max(240, Math.min(SoundRpm.F_HI, rpmValue / 60 * 8.8));
            if (subject != null) fTop = Math.max(fTop, Math.min(SoundRpm.F_HI, subject.hz * 1.2));
            fTop = Math.ceil(fTop / 100) * 100;
            lastFTop = fTop;
            spectrumView.setPlot(sound.df, db, n, true, "dB", Theme.SPEED, fTop, src, marks, selHz, live, "");
            return;
        }
        if (slow == null) {
            String hint = measurePage == PAGE_TAP ? (running ? "Tap the part now" : "Press Start, then tap the part")
                    : running ? "Collecting the first seconds…" : "Press Start to measure";
            spectrumView.setPlot(0, null, 0, false, "mg", Theme.INK, 100, src, marks, Double.NaN, live, hint);
            return;
        }
        double[] mg = new double[slow.amp.length];
        for (int k = 0; k < mg.length; k++) mg[k] = slow.amp[k] / G * 1000;
        for (int i = 0; i < Math.min(8, peaks.size()); i++) {
            Analysis.Peak p = peaks.get(i);
            Orders.Source m = Orders.bestMatch(src, p.hz, plotDf);
            marks.add(new SpectrumView.Mark(p.hz, p.amp / G * 1000, m == null ? Theme.INK : Theme.familyColor(m.family)));
        }
        double topF = 60;
        for (Orders.Source s : src) if (s.drawLine && s.family == Orders.Family.ENGINE && s.hz * 1.1 > topF) topF = s.hz * 1.1;
        if (subject != null) topF = Math.max(topF, subject.hz * 1.2);
        if (!peaks.isEmpty()) topF = Math.max(topF, peaks.get(0).hz * 1.2);
        topF = Math.min(topF, Math.min(200, slow.fs / 2));
        double step = topF > 120 ? 40 : 20;
        topF = Math.min(Math.max(step, Math.ceil(topF / step) * step), slow.fs / 2);
        lastFTop = topF;
        spectrumView.setPlot(slow.df, mg, mg.length, false, "mg", Theme.INK, topF, src, marks, selHz, live, "");
    }

    /** A sound peak's level in dBFS: the background around it plus how far it stands out. */
    private double peakDb(Analysis.Peak p) {
        if (sound == null) return -99;
        int k = Math.max(0, Math.min(sound.floor.length - 1, (int) Math.round(p.hz / sound.df)));
        return 20 * Math.log10(Math.max(sound.floor[k] * p.amp, 1e-9));
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        boolean tap = measurePage == PAGE_TAP;
        if (!running) sb.append(slow == null ? "Stopped" : "Stopped · last reading kept");
        else if (slow == null) sb.append("Collecting the first few seconds…");
        else sb.append(String.format(Locale.US, "Measuring · %.0f samples/s · %.0f s window", slow.fs, slow.seconds));
        if (frozen) sb.append(" · frozen");
        if (running && mic.isRunning() && sound != null) {
            sb.append(String.format(Locale.US, "\nMicrophone on · %.0f dBFS", sound.levelDb));
            if (sound.clipped) sb.append(" · too loud for the mic, move the phone away");
        } else if (running && micBox.isChecked() && !tap && mic.error() != null) {
            sb.append("\n").append(mic.error());
        }
        if (slow != null) {
            if (slow.gaps > 0) sb.append(String.format(Locale.US, "\nThe sensor skipped %d sample%s; they were filled in.", slow.gaps, slow.gaps == 1 ? "" : "s"));
            if (!tap && !Double.isNaN(rpmValue)) {
                double secondOrder = rpmValue / 30.0;
                if (slow.fs < secondOrder * 2.2) {
                    sb.append(String.format(Locale.US,
                            "\nWarning: %.0f samples/s is too slow to see %.0f Hz. Results above %.0f Hz are not reliable.",
                            slow.fs, secondOrder, slow.fs / 2.2));
                }
            }
        }
        status.setText(sb.toString());
        boolean live = running && !frozen;
        liveDotBg.setColor(live ? Theme.OK : Theme.MUTED);
        if (live && !dotPulse.isStarted()) dotPulse.start();
        if (!live && dotPulse.isStarted()) {
            dotPulse.cancel();
            liveDot.setAlpha(1f);
        }
    }

    private void updateGauges(double now) {
        if (rpmGaugeView == null) return;
        // Engine speed.
        double shown = rpmSrc == RpmSrc.OBD ? rpmGauge : rpmValue;
        if (!Double.isNaN(shown)) {
            if (shown > 3700 && rpmGaugeView.getScaleMax() < 8000) rpmGaugeView.setScale(8000, 2000);
            else if (shown < 3200 && rpmGaugeView.getScaleMax() > 4000) rpmGaugeView.setScale(4000, 1000);
        }
        rpmGaugeView.setValue(shown);
        rpmGaugeView.setLive(running && !frozen || rpmSrc == RpmSrc.OBD);
        String sub;
        int col = Theme.MUTED;
        switch (rpmSrc) {
            case OBD: sub = "from the ECU · exact"; col = Theme.OK; break;
            case ENGINE_OFF: sub = "ECU says engine off"; break;
            case LOCKED: sub = "locked to your tach"; col = Theme.OK; break;
            case VIB_SOUND: sub = "vibration + sound"; col = Theme.OK; break;
            case VIB:
                sub = rpmConf >= 0.75 ? "from vibration · sure" : rpmConf >= 0.45 ? "from vibration · fair" : "from vibration · unsure";
                col = rpmConf >= 0.75 ? Theme.OK : Theme.WARN;
                break;
            case SOUND: sub = "from engine sound"; col = rpmConf >= 0.75 ? Theme.OK : Theme.WARN; break;
            case TYPED: sub = rpmFallback ? "typed · can't measure" : "typed"; col = rpmFallback ? Theme.WARN : Theme.MUTED; break;
            default:
                sub = !running ? "press Start" : rpmMoving ? "engine speed moving" : "listening…";
        }
        if (rpmHolding) { sub = rpmMoving ? "engine speed moving" : "holding…"; col = Theme.WARN; }
        if (!running && !Double.isNaN(shown) && rpmSrc != RpmSrc.OBD && rpmSrc != RpmSrc.TYPED) sub = "last reading";
        rpmGaugeView.setSub(sub, col);

        // Road speed.
        boolean gpsWanted = gpsBox.isChecked();
        boolean gpsOk = gpsWanted && granted(Manifest.permission.ACCESS_FINE_LOCATION);
        double v;
        String ssub;
        int scol = Theme.MUTED;
        double g = gpsOk ? gpsSpeed.kmh(now) : Double.NaN;
        double o = obdSpeed.latest(now, 3);
        if (!Double.isNaN(g)) {
            v = g;
            double acc = gpsSpeed.accuracyKmh(now);
            ssub = "GPS" + (Double.isNaN(acc) ? "" : String.format(Locale.US, " ±%.1f", acc))
                    + (satsUsed > 0 ? " · " + satsUsed + " sats" : "");
            scol = Theme.OK;
        } else if (!Double.isNaN(o)) {
            v = o;
            ssub = gpsWanted ? "ECU · no GPS yet" : "from the ECU";
            scol = Theme.OK;
        } else if (!gpsWanted) {
            v = num(kmhIn);
            ssub = "typed · tap for GPS";
        } else {
            v = Double.NaN;
            if (!gpsOk) { ssub = "tap to allow location"; scol = Theme.WARN; }
            else if (!gpsEnabled()) { ssub = "location is off · tap"; scol = Theme.WARN; }
            else ssub = satsSeen >= 0 ? "satellites " + Math.max(0, satsUsed) + "/" + satsSeen : "waiting for GPS…";
        }
        if (!Double.isNaN(v)) {
            if (v > 150 && speedGaugeView.getScaleMax() < 240) speedGaugeView.setScale(240, 40);
            else if (v < 130 && speedGaugeView.getScaleMax() > 160) speedGaugeView.setScale(160, 40);
        }
        speedGaugeView.setValue(v);
        speedGaugeView.setLive(!Double.isNaN(g));
        speedGaugeView.setSub(ssub, scol);
    }

    /** "Tach shows about 786?" chips for the other readings the engine could be at. */
    private void updateChips(final double now) {
        boolean show = measurePage == PAGE_SHAKE && running && lastEst != null && autoBox.isChecked()
                && (lastEst.locked || !lastEst.alternatives.isEmpty());
        StringBuilder sig = new StringBuilder();
        if (show) {
            sig.append(lastEst.locked ? "L" : "A");
            for (int i = 0; i < Math.min(2, lastEst.alternatives.size()); i++) sig.append(Math.round(lastEst.alternatives.get(i) / 25.0)).append(',');
        }
        if (sig.toString().equals(chipSignature)) return;
        chipSignature = sig.toString();
        chipRow.removeAllViews();
        if (!show) {
            chipRow.setVisibility(View.GONE);
            return;
        }
        chipRow.setVisibility(View.VISIBLE);
        if (lastEst.locked) {
            TextView b = Ui.chip(this, "Locked to your tach · tap to release", Theme.OK);
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                vibTracker.unlock();
                soundTracker.unlock();
                chipSignature = "";
            });
            chipRow.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return;
        }
        for (int i = 0; i < Math.min(2, lastEst.alternatives.size()); i++) {
            final double alt = lastEst.alternatives.get(i);
            TextView b = Ui.chip(this, String.format(Locale.US, "Tach shows ≈ %.0f?", Math.round(alt / 10.0) * 10.0), Theme.ENGINE);
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                double t = nowSec();
                vibTracker.lock(alt, t);
                soundTracker.lock(alt, t);
                chipSignature = "";
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 8);
            chipRow.addView(b, lp);
        }
    }

    private void setVerdict(String chipText, int chipColor, String title, String what, String check, String note, double mg) {
        lastChip = chipText;
        lastTitle = title;
        lastWhat = what;
        lastCheck = check;
        chip.setText(chipText);
        chip.setTextColor(chipColor);
        resultTitle.setText(title);
        resultWhat.setText(what);
        resultCheck.setText(check);
        resultCheck.setVisibility(check.isEmpty() ? View.GONE : View.VISIBLE);
        resultNote.setText(note);
        resultNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        levelMeter.setLevel(mg);
        levelMeter.setVisibility(Double.isNaN(mg) ? View.GONE : View.VISIBLE);
        // Only animate when the verdict itself changed, not on every small number update.
        String coarse = chipText.split("·")[0] + "|" + title.split("·")[0];
        if (!coarse.equals(verdictSignature)) {
            verdictSignature = coarse;
            Ui.nudge(resultTitle);
        }
    }

    private void showWaiting(boolean tap, boolean soundView) {
        peaksCaption.setText("STRONGEST PEAKS");
        if (tap) {
            setVerdict(running ? "LISTENING" : "TAP TEST", Theme.MUTED,
                    running ? "Tap the part now" : "Press Start, then tap the part",
                    "Engine off and exhaust cold. Tape the phone firmly to the part, press Start, tap the part sharply 3–4 times, then press Freeze.",
                    "", "", Double.NaN);
        } else if (soundView) {
            setVerdict(running ? "LISTENING" : "NO READING YET", Theme.MUTED,
                    running ? (sound == null ? "Listening to the engine…" : "No clear tone in the sound") : "Press Start to hear the engine",
                    "Windows up, fan and radio off. The app looks for steady tones and matches them to engine speed and road speed.",
                    "", "", Double.NaN);
        } else if (running && slow != null) {
            setVerdict("TOO QUIET TO CALL", Theme.MUTED, "Nothing stands out of the noise",
                    "Either the car is smooth right now, or the phone is sitting on something soft. Lay it flat on the seat rail or the centre console.",
                    "", "", Double.NaN);
        } else {
            setVerdict(running ? "LISTENING" : "NO READING YET", Theme.MUTED,
                    running ? "Hold the engine steady" : "Press Start to measure",
                    "Lay the phone flat on the seat rail or centre console. Hold the rpm, under load if that's when it shakes.",
                    "", "", Double.NaN);
        }
    }

    private void showEngineResult(List<Orders.Source> src, Analysis.Peak top, double df) {
        double mg = top.amp / G * 1000;
        Orders.Source best = Orders.bestMatch(src, top.hz, df);
        Orders.Source rival = Orders.rival(src, best, top.hz, df);
        StringBuilder note = new StringBuilder();
        note.append(String.format(Locale.US, "%.1f mg · %s · %.0f× above the noise", mg,
                Theme.LEVEL_NAME[Theme.levelIndex(mg)].toLowerCase(Locale.US), top.snr));
        if (!Double.isNaN(top.vertFrac)) {
            note.append(top.vertFrac > 0.66 ? String.format(Locale.US, "\nDirection: mostly up-down (%.0f%%)", top.vertFrac * 100)
                    : top.vertFrac < 0.34 ? String.format(Locale.US, "\nDirection: mostly sideways or fore-aft (%.0f%%)", (1 - top.vertFrac) * 100)
                    : "\nDirection: a mix of up-down and sideways");
        }
        if (rpmMoving || (slow != null && !Double.isNaN(slow.drift) && slow.drift > 0.02)) {
            note.append("\nEngine speed moved during this reading. Hold it steadier for exact strengths.");
        }
        if (speedSrc == SpeedSrc.GPS && kmhValue > 5 && gpsSpeed.spreadKmh(nowSec(), SLOW_WIN) > Math.max(3, 0.08 * kmhValue)) {
            note.append("\nRoad speed changed during this reading. Hold a steady speed for the tyre and propshaft lines.");
        }
        if (speedSrc == SpeedSrc.NONE && gpsBox.isChecked()) {
            note.append("\nNo road speed yet, so tyre and propshaft aren't checked.");
        }
        double load = obdLoad.latest(nowSec(), 6);
        if (!Double.isNaN(load)) note.append(String.format(Locale.US, "\nEngine load %.0f%% (from the ECU)", load));
        if (Double.isNaN(rpmValue)) {
            note.append(rpmMoving ? "\nEngine speed is moving too fast to read."
                    : "\nEngine speed couldn't be read. Type it under Setup, or connect OBD2.");
        }

        if (best != null && rival != null) {
            setVerdict("TWO PARTS FIT", Theme.WARN,
                    String.format(Locale.US, "%s or %s · %.1f Hz", best.name, rival.name.toLowerCase(Locale.US), top.hz),
                    "Both turn at this frequency right now, so this reading can't tell them apart.",
                    "To separate them: hold the same rpm at a different road speed. The engine line stays put; the tyre and propshaft lines move.",
                    note.toString(), mg);
        } else if (best != null) {
            setVerdict(String.format(Locale.US, "MATCH · %s", pct(best.diff(top.hz))), Theme.OK,
                    String.format(Locale.US, "%s · %.1f Hz", best.name, top.hz),
                    best.what, "Check: " + best.check, note.toString(), mg);
        } else {
            Orders.Source near = Orders.nearest(src, top.hz);
            setVerdict("NO CLEAN MATCH", Theme.WARN,
                    String.format(Locale.US, "%.1f Hz doesn't line up with a turning part", top.hz),
                    near == null ? "Without engine speed there is nothing to compare this peak with."
                            : String.format(Locale.US, "Closest is %s at %.1f Hz (%s). A peak that stays put when rpm and speed change is usually a bracket, pipe or panel resonating on its own.",
                            near.name, near.hz, pct(near.diff(top.hz))),
                    "Use Tap test to find which part rings at this frequency.", note.toString(), mg);
        }
    }

    private void showSoundResult(List<Orders.Source> src, Analysis.Peak top) {
        Orders.Source best = Orders.bestMatch(src, top.hz, sound != null ? sound.df : 0.7);
        double above = 20 * Math.log10(top.amp);
        String note = String.format(Locale.US, "%.0f dB above the background · level %.0f dBFS (phone mics aren't calibrated, so compare readings, don't quote them)",
                above, peakDb(top));
        if (best == null) {
            setVerdict("NOT A TURNING PART", Theme.WARN,
                    String.format(Locale.US, "Tone at %.1f Hz", top.hz),
                    Double.isNaN(rpmValue) ? "Engine speed isn't known yet, so this tone can't be matched."
                            : "This tone doesn't follow engine speed or road speed. Likely the heater fan, a pump, wind, or a panel buzzing on its own.",
                    "Switch the fan off and see if it goes. If it stays at the same pitch when rpm changes, it isn't the engine.",
                    note, Double.NaN);
            return;
        }
        String what, check;
        double order = best.family == Orders.Family.ENGINE && rpmValue > 0 ? Math.round(top.hz / (rpmValue / 60) * 2) / 2.0 : -1;
        if (best.family == Orders.Family.ENGINE && order % 2 == 0) {
            what = "A firing-pulse sound: the boom or drone of exhaust and intake. Every four-cylinder makes it. It is a problem only when it gets loud at one rpm.";
            check = "Check: exhaust touching the body, loose heat shields, a hanger or bracket out of place, an intake pipe resting on something.";
        } else if (best.family == Orders.Family.ENGINE && order == 1) {
            what = "A once-per-turn sound from something at crank speed.";
            check = "Check: crank pulley, belt and idlers, anything rubbing the pulley.";
        } else if (best.family == Orders.Family.ENGINE) {
            what = "An uneven-firing sound. The cylinders aren't pulling equally, which gives a burble or a beat.";
            check = "Check: roughness monitor, coils, injectors, an exhaust leak near one cylinder.";
        } else if (best.family == Orders.Family.TYRE) {
            what = "A hum that follows road speed at wheel rate: tyre tread or a wheel bearing.";
            check = "Check: it changes with road surface (tyres) or when you swerve gently left and right (bearing).";
        } else {
            what = "A hum or whine that follows road speed at propshaft rate.";
            check = "Check: propshaft centre bearing, differential, U-joints.";
        }
        setVerdict(String.format(Locale.US, "MATCH · %s", pct(best.diff(top.hz))), Theme.OK,
                String.format(Locale.US, "%s · %.1f Hz", best.name, top.hz), what, check, note, Double.NaN);
    }

    private void showTapResult(Analysis.Peak top) {
        setVerdict("TAP TEST", Theme.OK,
                String.format(Locale.US, "This part rings at %.1f Hz", top.hz),
                String.format(Locale.US,
                        "The engine's 2nd order reaches %.1f Hz at %.0f rpm, and its 4th order at %.0f rpm. "
                                + "If either is where you feel the shake, this part is a prime suspect.",
                        top.hz, top.hz * 30, top.hz * 15),
                "Test each suspect part the same way and compare. Only the part ringing near your shake frequency matters.",
                "", Double.NaN);
    }

    private void updatePeakList(boolean soundView, boolean tap, List<Orders.Source> src, List<Analysis.Peak> peaks,
                                Analysis.Peak subject, double plotDf) {
        List<PeakListView.Row> rows = new ArrayList<>();
        double max = peaks.isEmpty() ? 1 : (soundView ? 20 * Math.log10(peaks.get(0).amp) : peaks.get(0).amp);
        for (int i = 0; i < Math.min(6, peaks.size()); i++) {
            Analysis.Peak p = peaks.get(i);
            Orders.Source m = tap ? null : Orders.bestMatch(src, p.hz, plotDf);
            String tag = m == null ? "–" : m.shortName;
            int col = m == null ? Theme.MUTED : Theme.familyColor(m.family);
            if (soundView) {
                double above = 20 * Math.log10(p.amp);
                rows.add(new PeakListView.Row(tag, col, p.hz, String.format(Locale.US, "+%.0f dB", above), above / max));
            } else if (tap) {
                rows.add(new PeakListView.Row("2E", Theme.ENGINE, p.hz, String.format(Locale.US, "%.0f rpm", p.hz * 30), p.amp / max));
            } else {
                rows.add(new PeakListView.Row(tag, col, p.hz, String.format(Locale.US, "%.1f mg", p.amp / G * 1000), p.amp / max));
            }
        }
        peaksCaption.setText(tap ? "RINGS AT · RPM WHERE 2ND ORDER HITS IT"
                : rows.isEmpty() ? "STRONGEST PEAKS" : "STRONGEST PEAKS · TAP ONE");
        peakList.setRows(rows, subject == null ? Double.NaN : subject.hz);
        peaksCard.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void pick(double hz) {
        selectedHz = hz;
        verdictSignature = "";
        refresh();
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

    // ---------------------------------------------------------------- GPS

    private boolean gpsEnabled() {
        try {
            return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void startGpsIfWanted(boolean mayAsk) {
        if (!gpsBox.isChecked() || locationManager == null || gpsListening) return;
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (mayAsk && !prefs.getBoolean("askedLoc", false)) {
                prefs.edit().putBoolean("askedLoc", true).apply();
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
            return;
        }
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper());
            gpsListening = true;
            if (gnssCallback == null) {
                gnssCallback = new GnssStatus.Callback() {
                    @Override public void onSatelliteStatusChanged(GnssStatus s) {
                        int used = 0;
                        for (int i = 0; i < s.getSatelliteCount(); i++) if (s.usedInFix(i)) used++;
                        satsUsed = used;
                        satsSeen = s.getSatelliteCount();
                    }
                };
            }
            locationManager.registerGnssStatusCallback(gnssCallback, handler);
        } catch (SecurityException | IllegalArgumentException e) {
            gpsListening = false; // no permission after all, or this device has no GPS
        }
    }

    private void stopGps() {
        if (locationManager == null) return;
        try {
            locationManager.removeUpdates(this);
            if (gnssCallback != null) locationManager.unregisterGnssStatusCallback(gnssCallback);
        } catch (RuntimeException ignored) { }
        gpsListening = false;
        gpsSpeed.clear();
        satsUsed = -1;
        satsSeen = -1;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l == null || !l.hasSpeed()) return;
        double acc = Double.NaN;
        if (Build.VERSION.SDK_INT >= 26 && l.hasSpeedAccuracy()) acc = l.getSpeedAccuracyMetersPerSecond();
        gpsSpeed.add(l.getElapsedRealtimeNanos() / 1e9, l.getSpeed(), acc);
        if (!running) updateGauges(nowSec());
    }

    @Override public void onProviderEnabled(String provider) { updateGauges(nowSec()); }
    @Override public void onProviderDisabled(String provider) { gpsSpeed.clear(); updateGauges(nowSec()); }
    @SuppressWarnings("deprecation")
    @Override public void onStatusChanged(String provider, int statusCode, Bundle extras) { }

    private void onSpeedGaugeTap() {
        if (gpsBox.isChecked()) {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                return;
            }
            if (!gpsEnabled()) {
                try {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (RuntimeException ignored) { }
                return;
            }
        }
        gpsBox.setChecked(!gpsBox.isChecked());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_LOCATION) {
            if (ok) startGpsIfWanted(false);
            updateGauges(nowSec());
        } else if (requestCode == REQ_MIC) {
            if (!ok) micBox.setChecked(false);
            else if (running && measurePage == PAGE_SHAKE) mic.start(this);
            applySourceSwitch();
            refresh();
        } else if (requestCode == REQ_BT) {
            int action = pendingBtAction;
            pendingBtAction = 0;
            if (!ok) {
                obdPage.setState(ObdLink.State.FAILED, "Bluetooth permission was refused.");
            } else if (action == 1) obdChooseAdapter();
            else if (action == 2) connectObd(true);
        }
    }

    // ---------------------------------------------------------------- OBD2

    private boolean btPermission(int action) {
        if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)) return true;
        pendingBtAction = action;
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        return false;
    }

    private boolean btReady(boolean prompt) {
        if (btAdapter == null || link == null) {
            obdPage.setState(ObdLink.State.FAILED, "This phone has no Bluetooth.");
            return false;
        }
        try {
            if (!btAdapter.isEnabled()) {
                obdPage.setState(ObdLink.State.FAILED, "Bluetooth is switched off.");
                if (prompt) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    @Override
    public void obdChooseAdapter() {
        if (!btPermission(1) || !btReady(true)) return;
        final List<BluetoothDevice> devices = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded != null) devices.addAll(bonded);
        } catch (SecurityException e) {
            obdPage.setState(ObdLink.State.FAILED, "Bluetooth permission is missing.");
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        if (devices.isEmpty()) {
            b.setTitle("No paired devices")
                    .setMessage("Plug the adapter into the car, switch the ignition on, then pair it in Android's Bluetooth settings. The PIN is usually 1234 or 0000.")
                    .setPositiveButton("Open Bluetooth settings", (d, w) -> {
                        try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (RuntimeException ignored) { }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < names.length; i++) {
            String n = null;
            try { n = devices.get(i).getName(); } catch (SecurityException ignored) { }
            names[i] = (n == null || n.isEmpty() ? "Unnamed device" : n) + "\n" + devices.get(i).getAddress();
        }
        b.setTitle("Choose your OBD2 adapter")
                .setItems(names, (d, which) -> {
                    String label = names[which].split("\n")[0];
                    prefs.edit().putString("obdMac", devices.get(which).getAddress()).putString("obdName", label).apply();
                    obdPage.setAdapterName(label);
                    connectObd(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void connectObd(boolean userAsked) {
        String mac = prefs.getString("obdMac", "");
        if (mac.isEmpty()) {
            if (userAsked) obdChooseAdapter();
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            if (userAsked) btPermission(2);
            return;
        }
        if (!btReady(userAsked)) return;
        prefs.edit().putBoolean("obd", true).apply();
        if (!obdBox.isChecked()) { loading = true; obdBox.setChecked(true); loading = false; }
        link.setLiveAll(page == PAGE_OBD);
        link.start(mac, prefs.getString("obdName", ""));
    }

    private void disconnectObd() {
        prefs.edit().putBoolean("obd", false).apply();
        if (obdBox.isChecked()) { loading = true; obdBox.setChecked(false); loading = false; }
        if (link != null) link.stop();
        obdRpm.clear();
        obdSpeed.clear();
    }

    @Override
    public void obdToggleConnection() {
        if (link != null && link.state() != ObdLink.State.OFF && link.state() != ObdLink.State.FAILED) disconnectObd();
        else connectObd(true);
    }

    @Override
    public void obdReadCodes() {
        if (link == null || !link.isLive()) return;
        obdPage.setBusy("Reading codes…");
        link.readCodes();
    }

    @Override
    public void obdClearCodes() {
        if (link == null || !link.isLive()) return;
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Clear all fault codes?")
                .setMessage("This erases the stored codes and the freeze frame, switches the check-engine light off, and resets the self-tests. The car may also forget its learned fuel trims and idle for a while. A fault that is still there will set its code again.\n\nDo it with the ignition on and the engine off.")
                .setPositiveButton("Clear codes", (d, w) -> {
                    obdPage.setBusy("Clearing codes…");
                    link.clearCodes();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void obdReadFreeze() {
        if (link != null && link.isLive()) link.readFreeze();
    }

    @Override
    public void onObdState(ObdLink.State state, String detail) {
        obdPage.setState(state, detail);
        if (state != ObdLink.State.LIVE) {
            obdRpm.clear();
            obdSpeed.clear();
        }
    }

    @Override
    public void onObdValue(int pid, double value, double tSec) {
        if (pid == 0x0C) obdRpm.add(tSec, value);
        else if (pid == 0x0D) obdSpeed.add(tSec, value);
        else if (pid == 0x04) obdLoad.add(tSec, value);
        obdLatest.put(pid, new double[]{value, tSec});
        obdPage.onValue(pid, value);
        if (pid == 0x0C && !running) liveSources();
    }

    @Override public void onObdInfo(String adapter, String protocol, double volts) { obdPage.setInfo(adapter, protocol, volts); }
    @Override public void onObdSupported(List<Integer> pids) {
        obdPids = new ArrayList<>(pids);
        obdPage.setSupported(pids);
    }
    @Override public void onObdCodes(ObdLink.Codes codes) { obdPage.setCodes(codes); }
    @Override public void onObdFreeze(ObdLink.Freeze freeze) { obdPage.setFreeze(freeze); }
    @Override public void onObdCleared(boolean ok) { obdPage.setCleared(ok); }

    // ---------------------------------------------------------------- inputs

    private void loadInputs() {
        loading = true;
        rpmIn.setText(prefs.getString("rpm2", ""));
        kmhIn.setText(prefs.getString("kmh", "0"));
        tyreIn.setText(prefs.getString("tyre", "225/55R18"));
        fdIn.setText(prefs.getString("fd", "3.700"));
        autoBox.setChecked(prefs.getBoolean("auto", true));
        gpsBox.setChecked(prefs.getBoolean("gps", true));
        micBox.setChecked(prefs.getBoolean("mic", false) && granted(Manifest.permission.RECORD_AUDIO));
        obdBox.setChecked(prefs.getBoolean("obd", false));
        obdPage.setAdapterName(prefs.getString("obdName", ""));
        page = prefs.getBoolean("tap", false) ? PAGE_TAP : PAGE_SHAKE;
        measurePage = page;
        pageSwitch.setSelected(page, false);
        kmhRow.setVisibility(gpsBox.isChecked() ? View.GONE : View.VISIBLE);
        setupBody.setVisibility(prefs.getBoolean("setupOpen", false) ? View.VISIBLE : View.GONE);
        setupHeader.setText(setupBody.getVisibility() == View.VISIBLE ? "SETUP  ▴" : "SETUP  ▾");
        loading = false;
        applySourceSwitch();
    }

    private void saveInputs() {
        prefs.edit()
                .putString("rpm2", rpmIn.getText().toString())
                .putString("kmh", kmhIn.getText().toString())
                .putString("tyre", tyreIn.getText().toString())
                .putString("fd", fdIn.getText().toString())
                .putBoolean("auto", autoBox.isChecked())
                .putBoolean("gps", gpsBox.isChecked())
                .putBoolean("mic", micBox.isChecked())
                .putBoolean("tap", measurePage == PAGE_TAP)
                .apply();
    }

    private final TextWatcher watcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) {
            if (loading) return;
            saveInputs();
            if (!running || frozen) { liveSources(); refresh(); }
        }
    };

    private void bumpRpm(int delta) {
        double v = Math.max(0, num(rpmIn) + delta);
        rpmIn.setText(String.format(Locale.US, "%.0f", v));
    }

    private void setPage(int p) {
        if (page == p) return;
        if ((p == PAGE_SHAKE || p == PAGE_TAP) && p != measurePage) {
            // Engine and Tap test measure differently: start clean.
            stopMeasuring();
            slow = null;
            slowPeaks = new ArrayList<>();
            sound = null;
            soundPeaks = new ArrayList<>();
            selectedHz = Double.NaN;
            verdictSignature = "";
            measurePage = p;
        }
        page = p;
        saveInputs();
        applyPage(true);
        refresh();
    }

    private void applyPage(boolean animate) {
        boolean obd = page == PAGE_OBD, saved = page == PAGE_SAVED, tap = measurePage == PAGE_TAP;
        shakePage.setVisibility(obd || saved ? View.GONE : View.VISIBLE);
        obdPage.root.setVisibility(obd ? View.VISIBLE : View.GONE);
        savedPage.root.setVisibility(saved ? View.VISIBLE : View.GONE);
        if (saved) savedPage.reload(); else savedPage.stopAudio();
        recBtn.setVisibility(tap ? View.GONE : View.VISIBLE);
        gaugeRow.setVisibility(tap ? View.GONE : View.VISIBLE);
        inputsCard.setVisibility(tap ? View.GONE : View.VISIBLE);
        legend.setVisibility(tap ? View.GONE : View.VISIBLE);
        if (tap) chipRow.setVisibility(View.GONE);
        chipSignature = "?";
        applySourceSwitch();
        intro.setText(saved ? "Your saved readings and recorded runs."
                : obd ? "Live data, fault codes and exact rpm from the engine computer."
                : tap ? "Tap test: finds the frequency a part rings at. Engine off."
                : "Measures the shake, then names the part that turns at that frequency.");
        if (link != null) link.setLiveAll(obd || recorder.isRecording());
        // Keep the screen awake on the live-data tab, like while measuring.
        if (obd) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else if (!running) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (animate) {
            Ui.enter(saved ? savedPage.root : obd ? obdPage.root : shakePage, 0);
            scroll.smoothScrollTo(0, 0);
        }
    }

    private void applySourceSwitch() {
        boolean engine = measurePage == PAGE_SHAKE;
        sourceSwitch.setVisibility(engine ? View.VISIBLE : View.GONE);
        if (showSound && (!engine || !micBox.isChecked())) {
            showSound = false;
            sourceSwitch.setSelected(0, true);
        }
    }

    private void updateButtons() {
        startBtn.setText(running ? "Stop" : "Start");
        Ui.paint(startBtn, running ? Theme.LINE : Theme.ENGINE, running ? Theme.INK : Theme.BG);
        freezeBtn.setEnabled(running);
        freezeBtn.setAlpha(running ? 1f : 0.4f);
        freezeBtn.setText(frozen ? "Resume" : "Freeze");
        boolean rec = recorder.isRecording();
        if (rec) {
            int sec = (int) recorder.seconds();
            recBtn.setText(String.format(Locale.US, "■ %d:%02d", sec / 60, sec % 60));
            if (!recPulse.isStarted()) {
                Ui.paint(recBtn, Theme.BAD, Theme.BG);
                recPulse.start();
            }
        } else if (recPulse.isStarted() || !"● Rec".contentEquals(recBtn.getText())) {
            recPulse.cancel();
            recBtn.setAlpha(1f);
            recBtn.setText("● Rec");
            Ui.paint(recBtn, Theme.alpha(Theme.BAD, 40), Theme.BAD);
        }
        if (running && !frozen) {
            if (!startPulse.isStarted()) startPulse.start();
        } else {
            startPulse.cancel();
            startBtn.setScaleX(1f);
            startBtn.setScaleY(1f);
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private View buildUi() {
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));
        scroll.addView(root);

        TextView title = Ui.text(this, 26, Theme.INK, true);
        title.setText("Shake Finder");
        root.addView(title, Ui.gapBelow(this, 2));
        intro = Ui.text(this, 14, Theme.MUTED, false);
        root.addView(intro, Ui.gapBelow(this, 12));

        pageSwitch = new SegmentView(this, "Engine", "Tap test", "OBD2", "Saved");
        pageSwitch.setListener(this::setPage);
        root.addView(pageSwitch, Ui.gapBelow(this, 12));

        shakePage = new LinearLayout(this);
        shakePage.setOrientation(LinearLayout.VERTICAL);
        shakePage.setLayoutTransition(new LayoutTransition());
        root.addView(shakePage);
        obdPage = new ObdPage(this, this);
        root.addView(obdPage.root);
        savedPage = new SavedPage(this, this);
        savedPage.root.setVisibility(View.GONE);
        root.addView(savedPage.root);

        // Gauges.
        gaugeRow = new LinearLayout(this);
        gaugeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout rpmCard = Ui.card(this);
        rpmCard.setPadding(dp(6), dp(10), dp(6), dp(6));
        rpmGaugeView = new GaugeView(this);
        rpmGaugeView.configure("RPM", 4000, 1000, 0, Theme.ENGINE);
        rpmGaugeView.setOnClickListener(v -> openSetup());
        rpmCard.addView(rpmGaugeView);
        LinearLayout speedCard = Ui.card(this);
        speedCard.setPadding(dp(6), dp(10), dp(6), dp(6));
        speedGaugeView = new GaugeView(this);
        speedGaugeView.configure("KM/H", 160, 40, 0, Theme.SPEED);
        speedGaugeView.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            onSpeedGaugeTap();
        });
        speedCard.addView(speedGaugeView);
        gaugeRow.addView(rpmCard, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sl.leftMargin = dp(10);
        gaugeRow.addView(speedCard, sl);
        shakePage.addView(gaugeRow, Ui.gapBelow(this, 10));

        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setVisibility(View.GONE);
        shakePage.addView(chipRow, Ui.gapBelow(this, 10));

        // Spectrum card.
        LinearLayout specCard = Ui.card(this);
        specCard.setLayoutTransition(new LayoutTransition());
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        liveDot = new View(this);
        liveDotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        liveDotBg.setColor(Theme.MUTED);
        liveDot.setBackground(liveDotBg);
        LinearLayout.LayoutParams ld = new LinearLayout.LayoutParams(dp(9), dp(9));
        ld.rightMargin = dp(8);
        ld.topMargin = dp(4);
        statusRow.addView(liveDot, ld);
        dotPulse = ObjectAnimator.ofFloat(liveDot, "alpha", 1f, 0.25f);
        dotPulse.setDuration(650);
        dotPulse.setRepeatMode(ValueAnimator.REVERSE);
        dotPulse.setRepeatCount(ValueAnimator.INFINITE);
        status = Ui.text(this, 12, Theme.MUTED, false);
        status.setTypeface(Theme.MONO);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        specCard.addView(statusRow, Ui.gapBelow(this, 8));
        sourceSwitch = new SegmentView(this, "Vibration", "Engine sound");
        sourceSwitch.setListener(i -> {
            showSound = i == 1;
            selectedHz = Double.NaN;
            verdictSignature = "";
            // Choosing the sound chart switches the microphone on (and asks for it the first time).
            if (showSound && !micBox.isChecked()) micBox.setChecked(true);
            refresh();
        });
        specCard.addView(sourceSwitch, Ui.gapBelow(this, 8));
        spectrumView = new SpectrumView(this);
        spectrumView.setListener(this::pick);
        specCard.addView(spectrumView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setPadding(0, dp(8), 0, 0);
        String[] names = {"E engine", "P propshaft", "T tyre", "● peak"};
        int[] cols = {Theme.ENGINE, Theme.PROPSHAFT, Theme.TYRE, Theme.INK};
        for (int i = 0; i < names.length; i++) {
            TextView t = Ui.text(this, 12, cols[i], true);
            t.setText(names[i]);
            t.setPadding(0, 0, dp(14), 0);
            legend.addView(t);
        }
        specCard.addView(legend);
        TextView chartTip = Ui.text(this, 12, Theme.MUTED, false);
        chartTip.setText("Drag across the chart to read any frequency.");
        chartTip.setPadding(0, dp(4), 0, 0);
        specCard.addView(chartTip);
        shakePage.addView(specCard, Ui.gapBelow(this, 12));

        // Start and Freeze.
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = Ui.button(this, "Start", Theme.ENGINE, Theme.BG);
        startBtn.setTextSize(17);
        freezeBtn = Ui.button(this, "Freeze", Theme.LINE, Theme.INK);
        freezeBtn.setTextSize(17);
        recBtn = Ui.button(this, "● Rec", Theme.alpha(Theme.BAD, 40), Theme.BAD);
        recBtn.setTextSize(17);
        recBtn.setContentDescription("Record a test run");
        recBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            toggleRecording();
        });
        recPulse = ObjectAnimator.ofFloat(recBtn, "alpha", 1f, 0.55f);
        recPulse.setDuration(600);
        recPulse.setRepeatMode(ValueAnimator.REVERSE);
        recPulse.setRepeatCount(ValueAnimator.INFINITE);
        btnRow.addView(startBtn, Ui.weight(this, 54, 0));
        btnRow.addView(freezeBtn, Ui.weight(this, 54, 8));
        btnRow.addView(recBtn, Ui.weight(this, 54, 8));
        startBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (running) stopMeasuring(); else startMeasuring();
        });
        freezeBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            frozen = !frozen;
            updateButtons();
            refresh();
        });
        startPulse = ObjectAnimator.ofPropertyValuesHolder(startBtn,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 0.97f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 0.97f));
        startPulse.setDuration(700);
        startPulse.setRepeatMode(ValueAnimator.REVERSE);
        startPulse.setRepeatCount(ValueAnimator.INFINITE);
        shakePage.addView(btnRow, Ui.gapBelow(this, 12));

        // Shake against rpm, filled in while a run is being recorded.
        sweepCard = Ui.card(this);
        sweepCard.addView(Ui.caption(this, "SHAKE AGAINST RPM"), Ui.gapBelow(this, 6));
        sweepText = Ui.text(this, 15, Theme.INK, true);
        sweepCard.addView(sweepText, Ui.gapBelow(this, 6));
        sweepView = new SweepView(this);
        sweepCard.addView(sweepView, Ui.gapBelow(this, 8));
        SegmentView sweepPick = new SegmentView(this, "All", "2E", "4E", "1E", "½E");
        sweepPick.setListener(i -> {
            sweepSeries = SWEEP_ORDER[i];
            updateSweep();
        });
        sweepCard.addView(sweepPick);
        sweepCard.setVisibility(View.GONE);
        shakePage.addView(sweepCard, Ui.gapBelow(this, 12));

        // Verdict card.
        LinearLayout res = Ui.card(this);
        res.setLayoutTransition(new LayoutTransition());
        chip = Ui.text(this, 12, Theme.MUTED, true);
        chip.setLetterSpacing(0.08f);
        res.addView(chip, Ui.gapBelow(this, 4));
        resultTitle = Ui.text(this, 21, Theme.INK, true);
        res.addView(resultTitle, Ui.gapBelow(this, 8));
        levelMeter = new LevelMeterView(this);
        res.addView(levelMeter, Ui.gapBelow(this, 8));
        resultWhat = Ui.text(this, 15, Theme.INK, false);
        res.addView(resultWhat, Ui.gapBelow(this, 6));
        resultCheck = Ui.text(this, 14, Theme.MUTED, false);
        res.addView(resultCheck, Ui.gapBelow(this, 8));
        resultNote = Ui.text(this, 12.5f, Theme.MUTED, false);
        resultNote.setTypeface(Theme.MONO);
        res.addView(resultNote, Ui.gapBelow(this, 10));
        saveBtn = Ui.button(this, "Save this reading", Theme.LINE, Theme.INK);
        saveBtn.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            saveReading();
        });
        res.addView(saveBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        shakePage.addView(res, Ui.gapBelow(this, 12));

        // Peaks card.
        peaksCard = Ui.card(this);
        peaksCaption = Ui.caption(this, "STRONGEST PEAKS");
        peaksCard.addView(peaksCaption, Ui.gapBelow(this, 6));
        peakList = new PeakListView(this);
        peakList.setListener(this::pick);
        peaksCard.addView(peakList);
        peaksCard.setVisibility(View.GONE);
        shakePage.addView(peaksCard, Ui.gapBelow(this, 12));

        // Setup card.
        inputsCard = Ui.card(this);
        inputsCard.setLayoutTransition(new LayoutTransition());
        setupHeader = Ui.caption(this, "SETUP  ▾");
        setupHeader.setMinHeight(dp(32));
        setupHeader.setGravity(Gravity.CENTER_VERTICAL);
        setupHeader.setOnClickListener(v -> toggleSetup());
        inputsCard.addView(setupHeader);
        setupBody = new LinearLayout(this);
        setupBody.setOrientation(LinearLayout.VERTICAL);
        setupBody.setPadding(0, dp(8), 0, 0);
        inputsCard.addView(setupBody);

        autoBox = Ui.toggle(this, "Measure engine speed automatically", Theme.ENGINE);
        autoBox.setOnCheckedChangeListener((b, on) -> { if (!loading) { saveInputs(); chipSignature = "?"; refresh(); } });
        setupBody.addView(autoBox, Ui.gapBelow(this, 4));

        micBox = Ui.toggle(this, "Use engine sound too (microphone)", Theme.SPEED);
        micBox.setOnCheckedChangeListener((b, on) -> {
            if (loading) return;
            if (on && !granted(Manifest.permission.RECORD_AUDIO)) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            } else if (on && running && measurePage == PAGE_SHAKE) {
                mic.start(this);
            } else if (!on) {
                mic.stop();
            }
            saveInputs();
            applySourceSwitch();
            refresh();
        });
        setupBody.addView(micBox);
        TextView micHelp = Ui.text(this, 12, Theme.MUTED, false);
        micHelp.setText("Sound backs up the vibration reading and adds a sound chart. Nothing is recorded or sent anywhere. Best with the fan and radio off.");
        setupBody.addView(micHelp, Ui.gapBelow(this, 8));

        obdBox = Ui.toggle(this, "Exact rpm from an OBD2 adapter", Theme.OK);
        obdBox.setOnCheckedChangeListener((b, on) -> {
            if (loading) return;
            if (on) {
                connectObd(true);
                if (prefs.getString("obdMac", "").isEmpty()) { loading = true; obdBox.setChecked(false); loading = false; }
            } else disconnectObd();
        });
        setupBody.addView(obdBox, Ui.gapBelow(this, 10));

        rpmIn = Ui.field(this, InputType.TYPE_CLASS_NUMBER, "e.g. 1400");
        rpmIn.addTextChangedListener(watcher);
        rpmIn.setGravity(Gravity.CENTER);
        LinearLayout rpmRow = Ui.row(this);
        TextView minus = Ui.button(this, "−100", Theme.LINE, Theme.INK);
        TextView plus = Ui.button(this, "+100", Theme.LINE, Theme.INK);
        minus.setOnClickListener(v -> bumpRpm(-100));
        plus.setOnClickListener(v -> bumpRpm(100));
        rpmRow.addView(minus, new LinearLayout.LayoutParams(dp(84), dp(48)));
        rpmRow.addView(rpmIn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rpmRow.addView(plus, new LinearLayout.LayoutParams(dp(84), dp(48)));
        setupBody.addView(Ui.labelled(this, "Engine speed from the tach (rpm)", rpmRow,
                "Only used when the app can't measure it, or when automatic is off. Leave empty otherwise."), Ui.gapBelow(this, 12));

        gpsBox = Ui.toggle(this, "Road speed from GPS", Theme.SPEED);
        gpsBox.setOnCheckedChangeListener((b, on) -> {
            kmhRow.setVisibility(on ? View.GONE : View.VISIBLE);
            if (loading) return;
            saveInputs();
            if (on) startGpsIfWanted(true); else stopGps();
            liveSources();
            refresh();
        });
        setupBody.addView(gpsBox, Ui.gapBelow(this, 4));
        kmhIn = Ui.field(this, Ui.numberInput(), "0");
        kmhIn.addTextChangedListener(watcher);
        kmhRow = Ui.labelled(this, "Road speed (km/h)", kmhIn, "0 when parked in P, D or R");
        setupBody.addView(kmhRow, Ui.gapBelow(this, 12));

        tyreIn = Ui.field(this, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, "225/55R18");
        tyreIn.addTextChangedListener(watcher);
        setupBody.addView(Ui.labelled(this, "Tyre size", tyreIn, "Printed on the tyre sidewall, e.g. 225/55R18"), Ui.gapBelow(this, 12));

        fdIn = Ui.field(this, Ui.numberInput(), "3.700");
        fdIn.addTextChangedListener(watcher);
        setupBody.addView(Ui.labelled(this, "Final drive ratio", fdIn, "Confirm from the service manual or ID plate"));
        shakePage.addView(inputsCard, Ui.gapBelow(this, 12));

        TextView tip = Ui.text(this, 13, Theme.MUTED, false);
        tip.setText("Tip: if two parts match at once, keep the same rpm at a different road speed. Engine lines stay put; tyre and propshaft lines move.");
        shakePage.addView(tip);

        // Cards rise into place when the app opens.
        for (int i = 0; i < shakePage.getChildCount(); i++) Ui.enter(shakePage.getChildAt(i), 40L * i);

        updateButtons();
        return scroll;
    }

    private void toggleSetup() {
        boolean open = setupBody.getVisibility() != View.VISIBLE;
        setupBody.setVisibility(open ? View.VISIBLE : View.GONE);
        setupHeader.setText(open ? "SETUP  ▴" : "SETUP  ▾");
        prefs.edit().putBoolean("setupOpen", open).apply();
    }

    private void openSetup() {
        if (setupBody.getVisibility() != View.VISIBLE) toggleSetup();
        scroll.post(() -> scroll.smoothScrollTo(0, shakePage.getTop() + inputsCard.getTop()));
    }
}
