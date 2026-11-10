package app.sitestamp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Keeps the list of project sites on the phone. */
final class ProjectStore {
    private static final String KEY = "projects";
    private final SharedPreferences sp;

    ProjectStore(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("sitestamp", Context.MODE_PRIVATE);
    }

    List<Project> load() {
        List<Project> out = new ArrayList<>();
        String raw = sp.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Project p = new Project();
                p.id = o.optString("id", "");
                p.name = o.optString("name", "");
                p.company = o.optString("company", "");
                p.place = o.optString("place", "");
                p.lat = o.optDouble("lat", 0);
                p.lon = o.optDouble("lon", 0);
                p.radiusM = o.optDouble("radius", 150);
                if (!p.id.isEmpty() && !p.name.isEmpty()) out.add(p);
            }
        } catch (JSONException ignored) {
            // A damaged list reads as empty rather than crashing the camera.
        }
        return out;
    }

    void save(List<Project> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Project p : list) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("company", p.company);
                o.put("place", p.place);
                o.put("lat", p.lat);
                o.put("lon", p.lon);
                o.put("radius", p.radiusM);
                arr.put(o);
            }
        } catch (JSONException ignored) {
            return;
        }
        sp.edit().putString(KEY, arr.toString()).apply();
    }

    static String newId() {
        return Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(new Random().nextInt(1296), 36);
    }
}
