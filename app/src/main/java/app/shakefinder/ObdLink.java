package app.shakefinder;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Talks to an ELM327 OBD2 adapter over classic Bluetooth (serial port profile).
 *
 * One worker thread owns the connection. It sets the adapter up, finds out
 * which values the car supports, then loops: engine speed every pass, road
 * speed every third pass, and one of the other values in turn. Requests from
 * the screen (read codes, clear codes, freeze frame) are slotted in between.
 * Everything is reported back on the main thread.
 */
final class ObdLink {

    enum State { OFF, CONNECTING, ADAPTER, SEARCHING, LIVE, ECU_SILENT, RETRY, FAILED }

    static final class Codes {
        final List<String> stored = new ArrayList<>();
        final List<String> pending = new ArrayList<>();
        final List<String> permanent = new ArrayList<>();
        Elm327.Readiness readiness;
    }

    static final class Freeze {
        String code; // null when the car holds no freeze frame
        final Map<Integer, Double> values = new LinkedHashMap<>();
    }

    interface Listener {
        void onObdState(State state, String detail);
        void onObdValue(int pid, double value, double tSec);
        void onObdInfo(String adapter, String protocol, double volts);
        void onObdSupported(List<Integer> pids);
        void onObdCodes(Codes codes);
        void onObdFreeze(Freeze freeze);
        void onObdCleared(boolean ok);
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int JOB_CODES = 1, JOB_CLEAR = 2, JOB_FREEZE = 3;
    private static final int[] FREEZE_PIDS = {0x0C, 0x0D, 0x04, 0x11, 0x05, 0x0F, 0x0B, 0x10, 0x0E, 0x06, 0x07};

    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<Integer> jobs = new ConcurrentLinkedQueue<>();

    private volatile boolean enabled = false;
    private volatile boolean liveAll = false;
    private volatile State state = State.OFF;
    private volatile Thread worker;
    private String mac, deviceName = "adapter";
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private boolean oneSuffix = true, can = true;
    private int silentReads = 0;

    ObdLink(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    State state() {
        return state;
    }

    boolean isLive() {
        return state == State.LIVE;
    }

    /** Poll every supported value (the OBD tab is open) or only what the shake analysis needs. */
    void setLiveAll(boolean all) {
        liveAll = all;
    }

    void readCodes() { jobs.add(JOB_CODES); }
    void clearCodes() { jobs.add(JOB_CLEAR); }
    void readFreeze() { jobs.add(JOB_FREEZE); }

    /** The caller must already hold the Bluetooth permission. */
    synchronized void start(String mac, String name) {
        if (enabled && mac != null && mac.equals(this.mac)) return;
        stop();
        this.mac = mac;
        this.deviceName = name == null || name.isEmpty() ? "adapter" : name;
        enabled = true;
        jobs.clear();
        worker = new Thread(this::run, "obd");
        worker.start();
    }

    synchronized void stop() {
        enabled = false;
        Thread w = worker;
        worker = null;
        closeQuietly();
        if (w != null) w.interrupt();
        if (state != State.OFF) post(State.OFF, "Not connected");
    }

    // ------------------------------------------------------------------ worker

    /** True while the calling thread is still the one in charge of the link. */
    private boolean current() {
        return enabled && worker == Thread.currentThread();
    }

    private void run() {
        final Thread me = Thread.currentThread();
        int attempt = 0;
        while (current()) {
            try {
                session();
            } catch (SecurityException e) {
                post(State.FAILED, "Bluetooth permission is missing.");
                break;
            } catch (IllegalArgumentException e) {
                post(State.FAILED, "That adapter can't be used. Choose it again.");
                break;
            } catch (IOException e) {
                if (!current()) break;
                attempt++;
                post(State.RETRY, state == State.CONNECTING
                        ? "Can't reach " + deviceName + ". Is it plugged in and the ignition on? Trying again…"
                        : "Connection lost. Trying again…");
            } catch (InterruptedException e) {
                break;
            }
            if (!current()) break;
            closeQuietly();
            try { Thread.sleep(Math.min(8000, 2500 + attempt * 1000L)); } catch (InterruptedException e) { break; }
        }
        // A newer worker may already own the socket; only tidy up if none does.
        if (worker == me || worker == null) closeQuietly();
    }

    @SuppressLint("MissingPermission")
    private void session() throws IOException, InterruptedException {
        post(State.CONNECTING, "Connecting to " + deviceName + "…");
        BluetoothDevice dev = adapter.getRemoteDevice(mac);
        BluetoothSocket s = dev.createRfcommSocketToServiceRecord(SPP);
        socket = s; // set before connecting, so stop() can cut a slow connect short
        try {
            s.connect();
        } catch (IOException first) {
            try { s.close(); } catch (IOException ignored) { }
            if (!current()) throw first;
            // Many clone adapters only accept the unauthenticated link.
            s = dev.createInsecureRfcommSocketToServiceRecord(SPP);
            socket = s;
            s.connect();
        }
        if (!current()) {
            try { s.close(); } catch (IOException ignored) { }
            throw new IOException("stopped");
        }
        in = s.getInputStream();
        out = s.getOutputStream();
        timeouts = 0;

        post(State.ADAPTER, "Setting up the adapter…");
        raw("ATZ", 3500);            // reset
        raw("ATE0", 1500);           // no echo
        raw("ATL0", 1000);           // no line feeds
        raw("ATS0", 1000);           // no spaces
        raw("ATH0", 1000);           // no headers
        raw("ATAT1", 1000);          // adaptive timing
        raw("ATSP0", 1500);          // find the car's protocol automatically
        String id = pretty(raw("ATI", 1500));
        oneSuffix = true;
        silentReads = 0;

        post(State.SEARCHING, "Looking for the car's ECU…");
        String r = Elm327.clean(raw("0100", 12000));
        int tries = 0;
        while (!Elm327.ecuAnswered(r)) {
            if (!current()) return;
            if (++tries >= 2) post(State.ECU_SILENT, "The adapter is connected but the car isn't answering. Turn the ignition on.");
            Thread.sleep(2500);
            r = Elm327.clean(raw("0100", 12000));
        }

        // Which protocol did it find? 6 to 9 are CAN.
        String pn = Elm327.clean(raw("ATDPN", 1500)).replace("\r", "").replace("A", "");
        int proto = pn.isEmpty() ? 6 : Character.digit(pn.charAt(0), 16);
        can = proto < 0 || proto >= 6;
        String protoName = pretty(raw("ATDP", 1500)).replace("AUTO, ", "");

        // Which values does the car support? Each answer says whether there is another page.
        List<Integer> supported = new ArrayList<>();
        int base = 0;
        while (true) {
            boolean[] sup = Elm327.supported(r, base);
            if (sup == null) break;
            for (int i = 0; i < 31; i++) if (sup[i] && Elm327.pid(base + 1 + i) != null) supported.add(base + 1 + i);
            if (!sup[31] || base >= 0x60) break;
            base += 0x20;
            r = Elm327.clean(raw(String.format(Locale.US, "01%02X", base), 2500));
        }
        final List<Integer> supCopy = new ArrayList<>(supported);
        main.post(() -> listener.onObdSupported(supCopy));
        double volts = parseVolts(raw("ATRV", 1500));
        postInfo(id, protoName, volts);

        boolean hasRpm = supported.contains(0x0C) || supported.isEmpty();
        boolean hasSpeed = supported.contains(0x0D);
        List<Elm327.Pid> slow = new ArrayList<>();
        for (int pidId : supported) if (pidId != 0x0C && pidId != 0x0D) slow.add(Elm327.pid(pidId));
        // The few slow values the shake screen and the computed tiles always want.
        List<Elm327.Pid> basics = new ArrayList<>();
        for (Elm327.Pid p : slow) if (p.id == 0x04 || p.id == 0x33) basics.add(p);

        post(State.LIVE, "Live");
        Elm327.Pid rpm = Elm327.pid(0x0C), speed = Elm327.pid(0x0D);
        int cycle = 0, slowIdx = 0, basicIdx = 0;
        while (current()) {
            Integer job = jobs.poll();
            if (job != null) {
                runJob(job);
                continue;
            }
            cycle++;
            if (hasRpm) {
                boolean got = query(rpm);
                if (got) {
                    if (state != State.LIVE) post(State.LIVE, "Live");
                    silentReads = 0;
                } else if (++silentReads == 8) {
                    post(State.ECU_SILENT, "The car stopped answering. Is the ignition on?");
                }
            }
            if (hasSpeed && cycle % 3 == 0) query(speed);
            if (liveAll && !slow.isEmpty()) {
                query(slow.get(slowIdx++ % slow.size()));
            } else if (!basics.isEmpty() && cycle % 6 == 0) {
                query(basics.get(basicIdx++ % basics.size()));
            }
            if (cycle % 60 == 0) postInfo(id, protoName, parseVolts(raw("ATRV", 1200)));
            if (!hasRpm) Thread.sleep(120);
        }
    }

    private boolean query(Elm327.Pid p) throws IOException, InterruptedException {
        String r = Elm327.clean(raw(p.command() + (oneSuffix ? "1" : ""), 1500));
        if (oneSuffix && Elm327.rejected(r)) {
            oneSuffix = false; // an old adapter that doesn't know the "one answer is enough" hint
            r = Elm327.clean(raw(p.command(), 1500));
        }
        final double v = Elm327.parse(r, 1, p);
        if (Double.isNaN(v)) return false;
        final double t = SystemClock.elapsedRealtimeNanos() / 1e9;
        final int id = p.id;
        main.post(() -> listener.onObdValue(id, v, t));
        return true;
    }

    private void runJob(int job) throws IOException, InterruptedException {
        if (job == JOB_CLEAR) {
            String r = Elm327.clean(raw("04", 6000));
            final boolean ok = r.contains("44");
            main.post(() -> listener.onObdCleared(ok));
            Thread.sleep(600);
            job = JOB_CODES; // show the result straight away
        }
        if (job == JOB_CODES) {
            final Codes c = new Codes();
            c.stored.addAll(Elm327.parseDtcs(Elm327.clean(raw("03", 5000)), 0x03, can));
            c.pending.addAll(Elm327.parseDtcs(Elm327.clean(raw("07", 5000)), 0x07, can));
            c.permanent.addAll(Elm327.parseDtcs(Elm327.clean(raw("0A", 5000)), 0x0A, can));
            c.readiness = Elm327.parseReadiness(Elm327.clean(raw("0101", 2500)));
            main.post(() -> listener.onObdCodes(c));
        } else if (job == JOB_FREEZE) {
            final Freeze f = new Freeze();
            f.code = Elm327.parseFreezeDtc(Elm327.clean(raw("020200", 3000)));
            if (f.code != null) {
                for (int id : FREEZE_PIDS) {
                    Elm327.Pid p = Elm327.pid(id);
                    double v = Elm327.parse(Elm327.clean(raw(String.format(Locale.US, "02%02X00", id), 2500)), 2, p);
                    if (!Double.isNaN(v)) f.values.put(id, v);
                }
            }
            main.post(() -> listener.onObdFreeze(f));
        }
    }

    /** Sends one command and returns everything up to the adapter's ">" prompt. */
    private String raw(String cmd, int timeoutMs) throws IOException, InterruptedException {
        InputStream is = in;
        OutputStream os = out;
        if (is == null || os == null) throw new IOException("closed");
        byte[] buf = new byte[256];
        while (is.available() > 0) is.read(buf, 0, Math.min(buf.length, is.available())); // drop leftovers
        os.write((cmd + "\r").getBytes("US-ASCII"));
        os.flush();
        StringBuilder sb = new StringBuilder();
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (true) {
            int avail = is.available();
            if (avail > 0) {
                int n = is.read(buf, 0, Math.min(buf.length, avail));
                if (n < 0) throw new IOException("closed");
                for (int i = 0; i < n; i++) {
                    char ch = (char) (buf[i] & 0x7F);
                    if (ch == '>') {
                        timeouts = 0;
                        return sb.toString();
                    }
                    sb.append(ch);
                }
            } else {
                if (!current()) throw new IOException("stopped");
                if (SystemClock.elapsedRealtime() > deadline) {
                    // No prompt. A few in a row means the link is dead.
                    if (++timeouts >= 4) throw new IOException("adapter silent");
                    return sb.toString();
                }
                Thread.sleep(4);
            }
        }
    }

    private int timeouts = 0;

    private static String pretty(String rawText) {
        return rawText.replace('\r', ' ').replace('\n', ' ').replace(">", "").trim().replaceAll(" +", " ");
    }

    private static double parseVolts(String rawText) {
        String s = pretty(rawText).toUpperCase(Locale.US).replace("V", "").trim();
        try {
            double v = Double.parseDouble(s);
            return v > 5 && v < 20 ? v : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void post(final State s, final String detail) {
        state = s;
        main.post(() -> listener.onObdState(s, detail));
    }

    private void postInfo(final String id, final String proto, final double volts) {
        main.post(() -> listener.onObdInfo(id, proto, volts));
    }

    private void closeQuietly() {
        BluetoothSocket s = socket;
        socket = null;
        in = null;
        out = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }
}
