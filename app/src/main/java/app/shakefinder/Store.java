package app.shakefinder;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Saved readings and recorded runs. Everything lives in the app's own folder
 * on the phone, so no storage permission is needed, and it is removed when the
 * app is uninstalled. Each item is a small .json file; a run also has a .csv
 * log and, when the microphone was on, a .wav sound file with the same name.
 */
final class Store {
    private Store() {}

    static final class Item {
        final File json;
        final JSONObject data;
        final String base;

        Item(File json, JSONObject data) {
            this.json = json;
            this.data = data;
            String n = json.getName();
            this.base = n.substring(0, n.length() - 5);
        }

        boolean isRun() { return "run".equals(data.optString("type")); }
        long time() { return data.optLong("time"); }
        File csv() { return new File(json.getParentFile(), base + ".csv"); }
        File wav() { return new File(json.getParentFile(), base + ".wav"); }
    }

    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        File s = new File(d, "saved");
        if (!s.isDirectory()) s.mkdirs();
        return s;
    }

    /** A sortable time stamp for file names, e.g. 20261110_095700. */
    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    static String when(long timeMs) {
        return new SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(new Date(timeMs));
    }

    /** Newest first. Files that can't be read are skipped. */
    static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                out.add(new Item(f, new JSONObject(read(f))));
            } catch (IOException | JSONException e) {
                // a half-written or damaged file: leave it out of the list
            }
        }
        out.sort((a, b) -> Long.compare(b.time(), a.time()));
        return out;
    }

    static void write(File f, String text) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes("UTF-8"));
        }
    }

    static String read(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    static void delete(Item it) {
        it.csv().delete();
        it.wav().delete();
        new File(it.json.getParentFile(), it.base + "_reading.csv").delete();
        it.json.delete();
    }

    /** The first n values, rounded to this many decimals to keep the file small. */
    static JSONArray array(double[] v, int n, int decimals) throws JSONException {
        JSONArray a = new JSONArray();
        double scale = Math.pow(10, decimals);
        for (int i = 0; i < n; i++) {
            double x = Double.isNaN(v[i]) || Double.isInfinite(v[i]) ? 0 : v[i];
            a.put(Math.round(x * scale) / scale);
        }
        return a;
    }

    static JSONArray array(float[] v) throws JSONException {
        JSONArray a = new JSONArray();
        for (float x : v) a.put(Math.round(x * 100) / 100.0);
        return a;
    }

    static double[] doubles(JSONArray a) {
        if (a == null) return new double[0];
        double[] v = new double[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = a.optDouble(i, 0);
        return v;
    }

    static float[] floats(JSONArray a) {
        if (a == null) return new float[0];
        float[] v = new float[a.length()];
        for (int i = 0; i < v.length; i++) v[i] = (float) a.optDouble(i, -1);
        return v;
    }
}
