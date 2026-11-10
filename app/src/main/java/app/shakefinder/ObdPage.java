package app.shakefinder;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The OBD2 tab: connection, live data tiles, fault codes, self-test readiness
 * and the freeze frame.
 */
final class ObdPage {

    interface Host {
        void obdChooseAdapter();
        void obdToggleConnection();
        void obdReadCodes();
        void obdClearCodes();
        void obdReadFreeze();
    }

    final LinearLayout root;
    private final Context c;
    private final Host host;

    private final View dot;
    private final GradientDrawable dotBg = new GradientDrawable();
    private final ObjectAnimator dotPulse;
    private final TextView statusText, adapterText, infoText, connectBtn;
    private final LinearLayout tilesBox, codesBox, readinessBox, freezeBox;
    private final TextView tilesHint, milText, codesBtn, clearBtn, freezeBtn;
    private final Map<Integer, ObdTileView> tiles = new HashMap<>();
    private double map = Double.NaN, baro = Double.NaN, maf = Double.NaN, lambda = Double.NaN,
            speed = Double.NaN, fuelRate = Double.NaN;
    private boolean live = false;

    ObdPage(Context ctx, Host h) {
        c = ctx;
        host = h;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);

        // Connection.
        LinearLayout conn = Ui.card(c);
        conn.addView(Ui.caption(c, "OBD2 ADAPTER"), Ui.gapBelow(c, 8));
        LinearLayout st = Ui.row(c);
        dot = new View(c);
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Theme.MUTED);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(c, 10), Ui.dp(c, 10));
        dl.rightMargin = Ui.dp(c, 10);
        st.addView(dot, dl);
        statusText = Ui.text(c, 16, Theme.INK, true);
        statusText.setText("Not connected");
        st.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        conn.addView(st, Ui.gapBelow(c, 4));
        adapterText = Ui.text(c, 13, Theme.MUTED, false);
        conn.addView(adapterText, Ui.gapBelow(c, 2));
        infoText = Ui.text(c, 13, Theme.MUTED, false);
        infoText.setVisibility(View.GONE);
        conn.addView(infoText, Ui.gapBelow(c, 2));
        LinearLayout btns = Ui.row(c);
        TextView choose = Ui.button(c, "Choose adapter", Theme.LINE, Theme.INK);
        choose.setOnClickListener(v -> host.obdChooseAdapter());
        connectBtn = Ui.button(c, "Connect", Theme.SPEED, Theme.BG);
        connectBtn.setOnClickListener(v -> host.obdToggleConnection());
        btns.addView(choose, Ui.weight(c, 48, 0));
        btns.addView(connectBtn, Ui.weight(c, 48, 10));
        LinearLayout.LayoutParams bl = Ui.gapBelow(c, 0);
        bl.topMargin = Ui.dp(c, 10);
        conn.addView(btns, bl);
        root.addView(conn, Ui.gapBelow(c, 12));

        dotPulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f);
        dotPulse.setDuration(600);
        dotPulse.setRepeatMode(ValueAnimator.REVERSE);
        dotPulse.setRepeatCount(ValueAnimator.INFINITE);

        // Live data.
        LinearLayout liveCard = Ui.card(c);
        liveCard.addView(Ui.caption(c, "LIVE DATA"), Ui.gapBelow(c, 8));
        tilesHint = Ui.text(c, 14, Theme.MUTED, false);
        tilesHint.setText("Connect the adapter to see what the engine computer sees. Tap a tile for its lowest and highest values.");
        liveCard.addView(tilesHint);
        tilesBox = new LinearLayout(c);
        tilesBox.setOrientation(LinearLayout.VERTICAL);
        liveCard.addView(tilesBox);
        root.addView(liveCard, Ui.gapBelow(c, 12));

        // Fault codes.
        LinearLayout codes = Ui.card(c);
        codes.setLayoutTransition(new LayoutTransition());
        codes.addView(Ui.caption(c, "FAULT CODES"), Ui.gapBelow(c, 8));
        milText = Ui.text(c, 15, Theme.MUTED, false);
        milText.setText("Read the codes to see stored, pending and permanent faults.");
        codes.addView(milText, Ui.gapBelow(c, 8));
        codesBox = new LinearLayout(c);
        codesBox.setOrientation(LinearLayout.VERTICAL);
        codes.addView(codesBox);
        LinearLayout cb = Ui.row(c);
        codesBtn = Ui.button(c, "Read codes", Theme.LINE, Theme.INK);
        codesBtn.setOnClickListener(v -> host.obdReadCodes());
        clearBtn = Ui.button(c, "Clear codes", Theme.alpha(Theme.BAD, 40), Theme.BAD);
        clearBtn.setOnClickListener(v -> host.obdClearCodes());
        cb.addView(codesBtn, Ui.weight(c, 48, 0));
        cb.addView(clearBtn, Ui.weight(c, 48, 10));
        codes.addView(cb);
        root.addView(codes, Ui.gapBelow(c, 12));

        // Readiness.
        LinearLayout ready = Ui.card(c);
        ready.setLayoutTransition(new LayoutTransition());
        ready.addView(Ui.caption(c, "SELF-TESTS (READINESS)"), Ui.gapBelow(c, 8));
        readinessBox = new LinearLayout(c);
        readinessBox.setOrientation(LinearLayout.VERTICAL);
        TextView rh = Ui.text(c, 14, Theme.MUTED, false);
        rh.setText("Filled in when you read the codes. After codes are cleared, the car has to finish these tests again.");
        readinessBox.addView(rh);
        ready.addView(readinessBox);
        root.addView(ready, Ui.gapBelow(c, 12));

        // Freeze frame.
        LinearLayout freeze = Ui.card(c);
        freeze.setLayoutTransition(new LayoutTransition());
        freeze.addView(Ui.caption(c, "FREEZE FRAME"), Ui.gapBelow(c, 8));
        freezeBox = new LinearLayout(c);
        freezeBox.setOrientation(LinearLayout.VERTICAL);
        TextView fh = Ui.text(c, 14, Theme.MUTED, false);
        fh.setText("What the engine was doing at the moment a fault code was stored.");
        freezeBox.addView(fh);
        freeze.addView(freezeBox, Ui.gapBelow(c, 10));
        freezeBtn = Ui.button(c, "Read freeze frame", Theme.LINE, Theme.INK);
        freezeBtn.setOnClickListener(v -> host.obdReadFreeze());
        freeze.addView(freezeBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 48)));
        root.addView(freeze, Ui.gapBelow(c, 12));

        TextView tip = Ui.text(c, 13, Theme.MUTED, false);
        tip.setText("Works with ELM327 adapters that use classic Bluetooth. Pair the adapter in Android's Bluetooth settings first; the PIN is usually 1234 or 0000. Adapters that are Bluetooth LE only or Wi-Fi only won't appear.");
        root.addView(tip);

        setLive(false);
    }

    // ------------------------------------------------------------------ connection

    void setAdapterName(String name) {
        adapterText.setText(name == null || name.isEmpty() ? "No adapter chosen yet." : "Adapter: " + name);
    }

    void setState(ObdLink.State s, String detail) {
        statusText.setText(detail);
        int col;
        boolean pulse = false;
        switch (s) {
            case LIVE: col = Theme.OK; break;
            case CONNECTING: case ADAPTER: case SEARCHING: case RETRY: col = Theme.WARN; pulse = true; break;
            case ECU_SILENT: col = Theme.WARN; break;
            case FAILED: col = Theme.BAD; break;
            default: col = Theme.MUTED;
        }
        dotBg.setColor(col);
        if (pulse && !dotPulse.isStarted()) dotPulse.start();
        if (!pulse) {
            dotPulse.cancel();
            dot.setAlpha(1f);
        }
        boolean on = s != ObdLink.State.OFF && s != ObdLink.State.FAILED;
        connectBtn.setText(on ? "Disconnect" : "Connect");
        Ui.paint(connectBtn, on ? Theme.LINE : Theme.SPEED, on ? Theme.INK : Theme.BG);
        setLive(s == ObdLink.State.LIVE);
        if (s == ObdLink.State.OFF || s == ObdLink.State.FAILED) infoText.setVisibility(View.GONE);
    }

    private void setLive(boolean isLive) {
        live = isLive;
        for (TextView b : new TextView[]{codesBtn, clearBtn, freezeBtn}) {
            b.setEnabled(isLive);
            b.setAlpha(isLive ? 1f : 0.4f);
        }
    }

    void setInfo(String adapter, String protocol, double volts) {
        StringBuilder sb = new StringBuilder();
        if (adapter != null && !adapter.isEmpty()) sb.append(adapter);
        if (protocol != null && !protocol.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(protocol);
        if (!Double.isNaN(volts)) sb.append(sb.length() > 0 ? " · " : "").append(String.format(Locale.US, "battery %.1f V", volts));
        infoText.setText(sb.toString());
        infoText.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ live data

    void setSupported(List<Integer> pids) {
        tilesBox.removeAllViews();
        tiles.clear();
        map = baro = maf = lambda = speed = fuelRate = Double.NaN;
        LinearLayout row = null;
        int n = 0;
        boolean hasLambda = false;
        for (Elm327.Pid p : Elm327.PIDS) {
            boolean show;
            if (p.id == Elm327.BOOST) show = pids.contains(0x0B);
            else if (p.id == Elm327.ECONOMY) show = pids.contains(0x0D) && (pids.contains(0x10) || pids.contains(0x5E));
            else show = pids.contains(p.id);
            if (p.id == 0x24 && hasLambda) show = false; // same sensor, older way of reporting it
            if (!show) continue;
            if (p.id == 0x34) hasLambda = true;
            ObdTileView t = new ObdTileView(c, p);
            tiles.put(p.id, t);
            if (n % 2 == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                tilesBox.addView(row, Ui.gapBelow(c, 8));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (n % 2 == 1) lp.leftMargin = Ui.dp(c, 8);
            row.addView(t, lp);
            Ui.enter(t, Math.min(n, 12) * 25L);
            n++;
        }
        if (n % 2 == 1 && row != null) { // keep the last tile half width
            View spacer = new View(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.leftMargin = Ui.dp(c, 8);
            row.addView(spacer, lp);
        }
        tilesHint.setText(n == 0 ? "The car answered but listed no values this app knows."
                : n + " values from the engine computer. Tap a tile for its lowest and highest.");
    }

    void onValue(int pid, double v) {
        ObdTileView t = tiles.get(pid);
        if (t != null) t.push(v);
        switch (pid) {
            case 0x0B: map = v; pushComputed(Elm327.BOOST, Elm327.boost(map, baro)); break;
            case 0x33: baro = v; break;
            case 0x10: maf = v; break;
            case 0x34: case 0x24: lambda = v; break;
            case 0x5E: fuelRate = v; break;
            case 0x0D: speed = v; pushComputed(Elm327.ECONOMY, Elm327.economy(speed, maf, lambda, fuelRate)); break;
            default: break;
        }
    }

    private void pushComputed(int id, double v) {
        ObdTileView t = tiles.get(id);
        if (t != null && !Double.isNaN(v)) t.push(v);
    }

    // ------------------------------------------------------------------ codes

    void setBusy(String what) {
        milText.setText(what);
        milText.setTextColor(Theme.MUTED);
    }

    void setCodes(ObdLink.Codes codes) {
        codesBox.removeAllViews();
        int total = codes.stored.size() + codes.pending.size() + codes.permanent.size();
        Elm327.Readiness r = codes.readiness;
        if (total == 0) {
            milText.setText(r != null && r.mil ? "The check-engine light is commanded on, but no codes were returned."
                    : "No fault codes. The check-engine light is off.");
            milText.setTextColor(r != null && r.mil ? Theme.WARN : Theme.OK);
        } else {
            milText.setText(String.format(Locale.US, "%d code%s found. Check-engine light: %s.",
                    total, total == 1 ? "" : "s", r == null ? "unknown" : r.mil ? "ON" : "off"));
            milText.setTextColor(r != null && r.mil ? Theme.BAD : Theme.WARN);
        }
        Ui.nudge(milText);
        int i = 0;
        for (String code : codes.stored) addCode(code, "STORED", Theme.BAD, i++);
        for (String code : codes.pending) if (!codes.stored.contains(code)) addCode(code, "PENDING", Theme.WARN, i++);
        for (String code : codes.permanent) addCode(code, "PERMANENT", Theme.MUTED, i++);

        readinessBox.removeAllViews();
        if (r == null) {
            TextView t = Ui.text(c, 14, Theme.MUTED, false);
            t.setText("The car didn't report its self-tests.");
            readinessBox.addView(t);
        } else {
            for (String name : r.complete) addReady(name, true);
            for (String name : r.incomplete) addReady(name, false);
            if (r.complete.isEmpty() && r.incomplete.isEmpty()) {
                TextView t = Ui.text(c, 14, Theme.MUTED, false);
                t.setText("No self-tests listed.");
                readinessBox.addView(t);
            }
        }
    }

    private void addCode(String code, String kind, int color, int index) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = Ui.row(c);
        TextView ct = Ui.text(c, 17, Theme.INK, true);
        ct.setTypeface(Theme.MONO_BOLD);
        ct.setText(code);
        top.addView(ct);
        TextView k = Ui.text(c, 11, color, true);
        k.setText(kind);
        k.setLetterSpacing(0.08f);
        k.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.alpha(color, 40));
        bg.setCornerRadius(Ui.dp(c, 10));
        k.setBackground(bg);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        kl.leftMargin = Ui.dp(c, 10);
        top.addView(k, kl);
        box.addView(top);
        TextView d = Ui.text(c, 14, Theme.MUTED, false);
        d.setText(Elm327.dtcText(code));
        box.addView(d);
        codesBox.addView(box, Ui.gapBelow(c, 10));
        Ui.enter(box, index * 40L);
    }

    private void addReady(String name, boolean done) {
        LinearLayout row = Ui.row(c);
        TextView mark = Ui.text(c, 14, done ? Theme.OK : Theme.WARN, true);
        mark.setText(done ? "✓" : "…");
        mark.setGravity(Gravity.CENTER);
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(c, 24), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView t = Ui.text(c, 14, Theme.INK, false);
        t.setText(name);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView s = Ui.text(c, 13, done ? Theme.MUTED : Theme.WARN, false);
        s.setText(done ? "done" : "not finished");
        row.addView(s);
        readinessBox.addView(row, Ui.gapBelow(c, 4));
    }

    void setCleared(boolean ok) {
        milText.setText(ok ? "Codes cleared. Reading again…" : "The car refused to clear the codes. Try with the ignition on and the engine off.");
        milText.setTextColor(ok ? Theme.OK : Theme.WARN);
    }

    void setFreeze(ObdLink.Freeze f) {
        freezeBox.removeAllViews();
        if (f.code == null) {
            TextView t = Ui.text(c, 14, Theme.MUTED, false);
            t.setText("No freeze frame is stored. The car saves one only when a fault code sets.");
            freezeBox.addView(t);
            return;
        }
        TextView head = Ui.text(c, 15, Theme.INK, true);
        head.setText("Saved when " + f.code + " set: " + Elm327.dtcText(f.code));
        freezeBox.addView(head, Ui.gapBelow(c, 6));
        int i = 0;
        for (Map.Entry<Integer, Double> e : f.values.entrySet()) {
            Elm327.Pid p = Elm327.pid(e.getKey());
            if (p == null) continue;
            LinearLayout row = Ui.row(c);
            TextView n = Ui.text(c, 14, Theme.MUTED, false);
            n.setText(p.name);
            row.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView v = Ui.text(c, 14, Theme.INK, false);
            v.setTypeface(Theme.MONO);
            v.setText(p.format(e.getValue()) + " " + p.unit);
            row.addView(v);
            freezeBox.addView(row, Ui.gapBelow(c, 3));
            Ui.enter(row, i++ * 30L);
        }
    }

    void stop() {
        dotPulse.cancel();
    }
}
