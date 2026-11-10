package app.shakefinder;

import android.animation.LayoutTransition;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Saved tab: every saved reading and recorded run, newest first. Tap one
 * to open it. Readings can be compared two at a time; runs show their
 * shake-against-rpm map and can play back their engine sound. Anything can be
 * shared or deleted.
 */
final class SavedPage {

    interface Host {
        void savedShare(File f);
        void savedScrollTop();
    }

    final LinearLayout root;
    private final Context c;
    private final Host host;
    private final TextView hint;
    private final LinearLayout compareBox, listBox;
    private Store.Item compareA;
    private LinearLayout openBody;
    private MediaPlayer player;
    private TextView playingBtn;

    SavedPage(Context ctx, Host h) {
        c = ctx;
        host = h;
        root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutTransition(new LayoutTransition());
        hint = Ui.text(c, 14, Theme.MUTED, false);
        root.addView(hint, Ui.gapBelow(c, 12));
        compareBox = new LinearLayout(c);
        compareBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(compareBox);
        listBox = new LinearLayout(c);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);
    }

    void reload() {
        stopAudio();
        listBox.removeAllViews();
        openBody = null;
        List<Store.Item> items = Store.list(c);
        if (items.isEmpty()) {
            hint.setText("Nothing saved yet.\n\nOn the Engine tab, press Rec to record a test run, or press \"Save this reading\" under a result to keep it. Saved items show up here, where you can reopen, compare and share them.");
        } else {
            hint.setText(items.size() + (items.size() == 1 ? " saved item." : " saved items.") + " Tap one to open it.");
        }
        int i = 0;
        for (Store.Item it : items) {
            View card = card(it);
            listBox.addView(card, Ui.gapBelow(c, 10));
            if (i < 8) Ui.enter(card, 30L * i);
            i++;
        }
    }

    void stopAudio() {
        if (player != null) {
            try { player.stop(); } catch (IllegalStateException ignored) { }
            player.release();
            player = null;
        }
        if (playingBtn != null) {
            playingBtn.setText("Play sound");
            playingBtn = null;
        }
    }

    // ------------------------------------------------------------------ list

    private View card(final Store.Item it) {
        final LinearLayout card = Ui.card(c);
        card.setLayoutTransition(new LayoutTransition());
        JSONObject d = it.data;
        boolean run = it.isRun();
        String mode = d.optString("mode", "engine");

        LinearLayout head = Ui.row(c);
        int col = run ? Theme.BAD : "tap".equals(mode) ? Theme.PROPSHAFT : "sound".equals(mode) ? Theme.SPEED : Theme.ENGINE;
        TextView badge = Ui.text(c, 11, col, true);
        badge.setText(run ? "RUN" : "tap".equals(mode) ? "TAP TEST" : "sound".equals(mode) ? "SOUND" : "READING");
        badge.setLetterSpacing(0.08f);
        badge.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.alpha(col, 40));
        bg.setCornerRadius(Ui.dp(c, 10));
        badge.setBackground(bg);
        head.addView(badge);
        TextView when = Ui.text(c, 12, Theme.MUTED, false);
        when.setText(Store.when(it.time()));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.leftMargin = Ui.dp(c, 10);
        head.addView(when, wl);
        card.addView(head, Ui.gapBelow(c, 6));

        String note = d.optString("note", "");
        TextView title = Ui.text(c, 17, Theme.INK, true);
        title.setText(!note.isEmpty() ? note : run ? "Test run" : d.optString("title", "Reading"));
        card.addView(title, Ui.gapBelow(c, 2));

        TextView sub = Ui.text(c, 13, Theme.MUTED, false);
        sub.setText(run ? runSummary(d) : readingSummary(d, !note.isEmpty()));
        card.addView(sub);

        final LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        body.setPadding(0, Ui.dp(c, 10), 0, 0);
        card.addView(body);

        card.setClickable(true);
        card.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            if (open && body.getChildCount() == 0) {
                if (it.isRun()) buildRun(it, body); else buildReading(it, body);
            }
            if (open && openBody != null && openBody != body) openBody.setVisibility(View.GONE);
            if (!open) stopAudio();
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            openBody = open ? body : null;
        });
        return card;
    }

    private static String runSummary(JSONObject d) {
        StringBuilder sb = new StringBuilder();
        double s = d.optDouble("seconds", 0);
        sb.append(String.format(Locale.US, "%d:%02d long", (int) (s / 60), (int) (s % 60)));
        double lo = d.optDouble("rpmMin", Double.NaN), hi = d.optDouble("rpmMax", Double.NaN);
        if (!Double.isNaN(lo) && !Double.isNaN(hi)) sb.append(String.format(Locale.US, " · %,.0f–%,.0f rpm", lo, hi));
        double mg = d.optDouble("maxMg", 0), at = d.optDouble("maxRpm", Double.NaN);
        if (mg > 0) {
            sb.append(String.format(Locale.US, "\nStrongest shake %.1f mg", mg));
            if (!Double.isNaN(at)) sb.append(String.format(Locale.US, " at %,.0f rpm", at));
        }
        if (d.optBoolean("hasWav")) sb.append("\nEngine sound saved");
        return sb.toString();
    }

    private static String readingSummary(JSONObject d, boolean withTitle) {
        StringBuilder sb = new StringBuilder();
        if (withTitle) sb.append(d.optString("title", "")).append('\n');
        double rpm = d.optDouble("rpm", Double.NaN), kmh = d.optDouble("kmh", Double.NaN);
        if (!Double.isNaN(rpm)) sb.append(String.format(Locale.US, "%,.0f rpm", rpm));
        if (!Double.isNaN(kmh) && kmh > 0) sb.append(sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n' ? " · " : "").append(String.format(Locale.US, "%.0f km/h", kmh));
        JSONArray peaks = d.optJSONArray("peaks");
        if (peaks != null && peaks.length() > 0) {
            JSONObject p = peaks.optJSONObject(0);
            if (p != null) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append(" · ");
                sb.append(String.format(Locale.US, "top peak %.1f Hz, %s", p.optDouble("hz"), p.optString("text")));
            }
        }
        return sb.toString().trim();
    }

    private LinearLayout buttons(TextView... bs) {
        LinearLayout row = Ui.row(c);
        for (int i = 0; i < bs.length; i++) row.addView(bs[i], Ui.weight(c, 48, i == 0 ? 0 : 8));
        return row;
    }

    private void confirmDelete(final Store.Item it) {
        new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Delete this item?")
                .setMessage(it.isRun() ? "The log, the rpm map and any saved engine sound for this run will be removed from the phone."
                        : "This saved reading will be removed from the phone.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (compareA != null && compareA.json.equals(it.json)) cancelCompare();
                    Store.delete(it);
                    reload();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ a saved reading

    private static List<Orders.Source> lines(JSONObject d) {
        if ("tap".equals(d.optString("mode"))) return new ArrayList<>();
        double rpm = d.optDouble("rpm", 0), kmh = d.optDouble("kmh", 0);
        return Orders.compute(Double.isNaN(rpm) ? 0 : rpm, Double.isNaN(kmh) ? 0 : kmh,
                d.optString("tyre", ""), d.optDouble("fd", 0), 0.015, 0.05);
    }

    private static List<SpectrumView.Mark> marks(JSONObject d, List<Orders.Source> src, boolean decibels) {
        List<SpectrumView.Mark> marks = new ArrayList<>();
        JSONArray peaks = d.optJSONArray("peaks");
        double df = d.optDouble("df", 0.4);
        if (peaks == null) return marks;
        for (int i = 0; i < peaks.length(); i++) {
            JSONObject p = peaks.optJSONObject(i);
            if (p == null) continue;
            Orders.Source m = Orders.bestMatch(src, p.optDouble("hz"), df);
            marks.add(new SpectrumView.Mark(p.optDouble("hz"), p.optDouble(decibels ? "y" : "v"),
                    m == null ? Theme.INK : Theme.familyColor(m.family)));
        }
        return marks;
    }

    private void buildReading(final Store.Item it, LinearLayout body) {
        JSONObject d = it.data;
        boolean db = "dB".equals(d.optString("unit"));
        double[] values = Store.doubles(d.optJSONArray("values"));
        List<Orders.Source> src = lines(d);

        SpectrumView chart = new SpectrumView(c);
        chart.setPlot(d.optDouble("df", 0.4), values, values.length, db, db ? "dB" : "mg", db ? Theme.SPEED : Theme.INK,
                d.optDouble("fTop", 100), src, marks(d, src, db), Double.NaN, false, "");
        body.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 210)));

        String what = d.optString("what", "");
        if (!what.isEmpty()) {
            TextView w = Ui.text(c, 14, Theme.INK, false);
            w.setText(what);
            LinearLayout.LayoutParams lp = Ui.gapBelow(c, 4);
            lp.topMargin = Ui.dp(c, 8);
            body.addView(w, lp);
        }
        String check = d.optString("check", "");
        if (!check.isEmpty()) {
            TextView w = Ui.text(c, 13, Theme.MUTED, false);
            w.setText(check);
            body.addView(w, Ui.gapBelow(c, 4));
        }

        List<PeakListView.Row> rows = new ArrayList<>();
        JSONArray peaks = d.optJSONArray("peaks");
        double max = 0;
        if (peaks != null) {
            for (int i = 0; i < peaks.length(); i++) {
                JSONObject p = peaks.optJSONObject(i);
                if (p != null) max = Math.max(max, p.optDouble("v", 0));
            }
        }
        if (peaks != null) {
            for (int i = 0; i < Math.min(6, peaks.length()); i++) {
                JSONObject p = peaks.optJSONObject(i);
                if (p == null) continue;
                Orders.Source m = Orders.bestMatch(src, p.optDouble("hz"), d.optDouble("df", 0.4));
                rows.add(new PeakListView.Row(p.optString("tag", "–"), m == null ? Theme.MUTED : Theme.familyColor(m.family),
                        p.optDouble("hz"), p.optString("text"), max > 0 ? p.optDouble("v", 0) / max : 0));
            }
        }
        PeakListView list = new PeakListView(c);
        list.setRows(rows, Double.NaN);
        body.addView(list, Ui.gapBelow(c, 8));

        TextView cmp = Ui.button(c, "Compare", Theme.LINE, Theme.INK);
        cmp.setOnClickListener(v -> pickForCompare(it));
        TextView share = Ui.button(c, "Share", Theme.LINE, Theme.INK);
        share.setOnClickListener(v -> shareReading(it));
        TextView del = Ui.button(c, "Delete", Theme.alpha(Theme.BAD, 40), Theme.BAD);
        del.setOnClickListener(v -> confirmDelete(it));
        body.addView(buttons(cmp, share, del));
    }

    /** Writes the reading out as a small table any spreadsheet can open, then shares it. */
    private void shareReading(Store.Item it) {
        JSONObject d = it.data;
        StringBuilder sb = new StringBuilder();
        sb.append("Shake Finder reading,").append(Store.when(it.time()).replace(",", "")).append('\n');
        sb.append("note,").append(d.optString("note", "").replace(',', ' ').replace('\n', ' ')).append('\n');
        sb.append("result,").append(d.optString("title", "").replace(',', ' ')).append('\n');
        sb.append("rpm,").append(d.optString("rpm", "")).append('\n');
        sb.append("speed_kmh,").append(d.optString("kmh", "")).append('\n');
        String unit = d.optString("unit", "mg");
        sb.append("\npeak_hz,peak_").append(unit).append(",match\n");
        JSONArray peaks = d.optJSONArray("peaks");
        if (peaks != null) {
            for (int i = 0; i < peaks.length(); i++) {
                JSONObject p = peaks.optJSONObject(i);
                if (p == null) continue;
                sb.append(String.format(Locale.US, "%.2f,%.2f,%s\n", p.optDouble("hz"), p.optDouble("dB".equals(unit) ? "y" : "v"), p.optString("tag")));
            }
        }
        sb.append("\nhz,").append(unit).append('\n');
        double df = d.optDouble("df", 0.4);
        double[] v = Store.doubles(d.optJSONArray("values"));
        for (int i = 0; i < v.length; i++) sb.append(String.format(Locale.US, "%.2f,%.2f\n", i * df, v[i]));
        File f = new File(it.json.getParentFile(), it.base + "_reading.csv");
        try {
            Store.write(f, sb.toString());
            host.savedShare(f);
        } catch (IOException e) {
            hint.setText("That reading couldn't be written out for sharing.");
        }
    }

    // ------------------------------------------------------------------ comparing two readings

    private void pickForCompare(Store.Item it) {
        if (compareA == null || compareA.json.equals(it.json)) {
            compareA = it;
            compareBox.removeAllViews();
            LinearLayout card = Ui.card(c);
            TextView t = Ui.text(c, 15, Theme.INK, false);
            t.setText("First reading chosen: " + label(it) + "\n\nNow open a second reading and press Compare on it.");
            card.addView(t, Ui.gapBelow(c, 10));
            TextView cancel = Ui.button(c, "Cancel comparison", Theme.LINE, Theme.INK);
            cancel.setOnClickListener(v -> cancelCompare());
            card.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 48)));
            compareBox.addView(card, Ui.gapBelow(c, 12));
            host.savedScrollTop();
            return;
        }
        showCompare(compareA, it);
        compareA = null;
    }

    private void cancelCompare() {
        compareA = null;
        compareBox.removeAllViews();
    }

    private static String label(Store.Item it) {
        String note = it.data.optString("note", "");
        return (note.isEmpty() ? it.data.optString("title", "Reading") : note) + " (" + Store.when(it.time()) + ")";
    }

    private void showCompare(Store.Item a, Store.Item b) {
        compareBox.removeAllViews();
        LinearLayout card = Ui.card(c);
        card.addView(Ui.caption(c, "COMPARISON"), Ui.gapBelow(c, 8));
        JSONObject da = a.data, db = b.data;
        String unit = da.optString("unit", "mg");
        if (!unit.equals(db.optString("unit", "mg"))) {
            TextView t = Ui.text(c, 15, Theme.WARN, false);
            t.setText("These two can't be compared: one is a vibration reading and the other is a sound reading.");
            card.addView(t, Ui.gapBelow(c, 10));
        } else {
            boolean dec = "dB".equals(unit);
            TextView la = Ui.text(c, 14, Theme.INK, true);
            la.setText("━  " + label(a));
            card.addView(la, Ui.gapBelow(c, 2));
            TextView lb = Ui.text(c, 14, Theme.ENGINE, true);
            lb.setText("━  " + label(b));
            card.addView(lb, Ui.gapBelow(c, 8));

            double[] va = Store.doubles(da.optJSONArray("values")), vb = Store.doubles(db.optJSONArray("values"));
            SpectrumView chart = new SpectrumView(c);
            chart.setGhost(db.optDouble("df", 0.4), vb, vb.length, Theme.ENGINE);
            List<Orders.Source> src = lines(da);
            chart.setPlot(da.optDouble("df", 0.4), va, va.length, dec, unit, dec ? Theme.SPEED : Theme.INK,
                    Math.max(da.optDouble("fTop", 100), db.optDouble("fTop", 100)), src, marks(da, src, dec), Double.NaN, false, "");
            card.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 220)));

            // Part by part: what changed.
            Map<String, double[]> table = new LinkedHashMap<>();
            collect(table, da, 0, dec);
            collect(table, db, 1, dec);
            TextView rows = Ui.text(c, 13, Theme.INK, false);
            rows.setTypeface(Theme.MONO);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%-9s %9s %9s  %s\n", "", "first", "second", "change"));
            for (Map.Entry<String, double[]> e : table.entrySet()) {
                double x = e.getValue()[0], y = e.getValue()[1];
                String change;
                if (Double.isNaN(x) || Double.isNaN(y)) change = Double.isNaN(y) ? "gone" : "new";
                else if (dec) change = String.format(Locale.US, "%+.0f dB", y - x);
                else change = x > 0 ? String.format(Locale.US, "%+.0f%%", (y / x - 1) * 100) : "";
                sb.append(String.format(Locale.US, "%-9s %9s %9s  %s\n", e.getKey(), cell(x, dec), cell(y, dec), change));
            }
            rows.setText(sb.toString().trim());
            LinearLayout.LayoutParams rl = Ui.gapBelow(c, 10);
            rl.topMargin = Ui.dp(c, 8);
            card.addView(rows, rl);
            if (table.size() <= 1) {
                TextView t = Ui.text(c, 13, Theme.MUTED, false);
                t.setText("Neither reading had a peak matched to a part, so only the strongest peaks are compared.");
                card.addView(t, Ui.gapBelow(c, 10));
            }
        }
        TextView close = Ui.button(c, "Close comparison", Theme.LINE, Theme.INK);
        close.setOnClickListener(v -> cancelCompare());
        card.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 48)));
        compareBox.addView(card, Ui.gapBelow(c, 12));
        Ui.enter(card, 0);
        host.savedScrollTop();
    }

    private static String cell(double v, boolean dec) {
        if (Double.isNaN(v)) return "–";
        return dec ? String.format(Locale.US, "+%.0f dB", v) : String.format(Locale.US, "%.1f mg", v);
    }

    /** Adds one reading's strongest peak and its matched peaks to the comparison table. */
    private static void collect(Map<String, double[]> table, JSONObject d, int col, boolean dec) {
        JSONArray peaks = d.optJSONArray("peaks");
        if (peaks == null) return;
        for (int i = 0; i < peaks.length(); i++) {
            JSONObject p = peaks.optJSONObject(i);
            if (p == null) continue;
            String tag = p.optString("tag", "–");
            double v = p.optDouble("v", Double.NaN);
            if (i == 0) put(table, "Strongest", col, v);
            if (!"–".equals(tag)) put(table, tag, col, v);
        }
    }

    private static void put(Map<String, double[]> table, String key, int col, double v) {
        double[] row = table.get(key);
        if (row == null) {
            row = new double[]{Double.NaN, Double.NaN};
            table.put(key, row);
        }
        if (Double.isNaN(row[col])) row[col] = v;
    }

    // ------------------------------------------------------------------ a recorded run

    private void buildRun(final Store.Item it, LinearLayout body) {
        JSONObject d = it.data;
        JSONObject sw = d.optJSONObject("sweep");
        float[][] slices = new float[SweepMap.SERIES][];
        if (sw != null) for (int s = 0; s < SweepMap.SERIES; s++) slices[s] = Store.floats(sw.optJSONArray(SweepMap.KEYS[s]));
        final SweepMap map = sw == null ? new SweepMap() : SweepMap.restore(sw.optInt("firstBin", -1), slices);

        final TextView worst = Ui.text(c, 15, Theme.INK, true);
        final SweepView view = new SweepView(c);
        final int[] order = {SweepMap.PEAK, 2, 3, 1, 0};
        if (map.isEmpty()) {
            worst.setText("This run has no shake-against-rpm map: engine speed wasn't read while it was recording.");
            worst.setTextColor(Theme.MUTED);
            body.addView(worst, Ui.gapBelow(c, 8));
        } else {
            worst.setText(map.worstText(SweepMap.PEAK));
            body.addView(worst, Ui.gapBelow(c, 6));
            view.setData(map, SweepMap.PEAK, Double.NaN, false);
            body.addView(view, Ui.gapBelow(c, 8));
            SegmentView pick = new SegmentView(c, "All", "2E", "4E", "1E", "½E");
            pick.setListener(i -> {
                view.setData(map, order[i], Double.NaN, false);
                String t = map.worstText(order[i]);
                worst.setText(t.isEmpty() ? "Nothing at this order during the run." : t);
            });
            body.addView(pick, Ui.gapBelow(c, 10));
        }

        TextView info = Ui.text(c, 13, Theme.MUTED, false);
        info.setText(String.format(Locale.US, "%d readings logged, four a second: rpm, speed, peaks, each engine order%s.",
                d.optInt("rows"), d.optInt("obdColumns") > 0 ? " and " + d.optInt("obdColumns") + " OBD values" : ""));
        body.addView(info, Ui.gapBelow(c, 10));

        TextView share = Ui.button(c, "Share log", Theme.LINE, Theme.INK);
        share.setOnClickListener(v -> { if (it.csv().isFile()) host.savedShare(it.csv()); });
        TextView del = Ui.button(c, "Delete", Theme.alpha(Theme.BAD, 40), Theme.BAD);
        del.setOnClickListener(v -> confirmDelete(it));
        body.addView(buttons(share, del), Ui.gapBelow(c, 8));

        if (it.wav().isFile() && it.wav().length() > 44) {
            final TextView play = Ui.button(c, "Play sound", Theme.LINE, Theme.INK);
            play.setOnClickListener(v -> togglePlay(it.wav(), play));
            TextView sound = Ui.button(c, "Share sound", Theme.LINE, Theme.INK);
            sound.setOnClickListener(v -> host.savedShare(it.wav()));
            body.addView(buttons(play, sound));
        }
    }

    private void togglePlay(File wav, final TextView btn) {
        boolean wasThis = playingBtn == btn;
        stopAudio();
        if (wasThis) return;
        try {
            MediaPlayer p = new MediaPlayer();
            p.setDataSource(wav.getAbsolutePath());
            p.setOnCompletionListener(mp -> stopAudio());
            p.prepare();
            p.start();
            player = p;
            playingBtn = btn;
            btn.setText("Stop");
        } catch (IOException | RuntimeException e) {
            btn.setText("Can't play this file");
        }
    }
}
