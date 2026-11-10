package app.sitestamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** The list of project sites: add one where you stand, or type its coordinates. */
public final class ProjectsActivity extends Activity implements LocationTracker.Listener {

    private ProjectStore store;
    private List<Project> projects;
    private LocationTracker tracker;
    private Location here;
    private LinearLayout list;
    private TextView gpsLine;
    /** The coordinate box of the open edit window, so "use where I am" can fill it. */
    private EditText openCoords;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        store = new ProjectStore(this);
        projects = store.load();
        tracker = new LocationTracker(this, this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 18);

        LinearLayout head = Ui.column(this);
        head.setPadding(pad, pad, pad, Ui.dp(this, 8));
        head.addView(Ui.text(this, "Project sites", 24, Ui.TEXT, true));
        TextView hint = Ui.text(this, "Save each site once. When the phone is inside a site's "
                + "circle, its name goes on the stamp by itself.", 14, Ui.DIM, false);
        head.addView(hint, Ui.margins(Ui.fill(), 0, Ui.dp(this, 6), 0, 0));
        gpsLine = Ui.text(this, "", 13, Ui.DIM, false);
        head.addView(gpsLine, Ui.margins(Ui.fill(), 0, Ui.dp(this, 10), 0, 0));
        root.addView(head, Ui.fill());

        ScrollView scroll = new ScrollView(this);
        list = Ui.column(this);
        list.setPadding(pad, 0, pad, pad);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));

        LinearLayout buttons = Ui.column(this);
        buttons.setPadding(pad, Ui.dp(this, 10), pad, pad);
        TextView addHere = Ui.button(this, "Add the site I am standing on", true);
        addHere.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addHere();
            }
        });
        buttons.addView(addHere, Ui.fill());
        TextView addTyped = Ui.button(this, "Add a site by its coordinates", false);
        addTyped.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                edit(null, null);
            }
        });
        buttons.addView(addTyped, Ui.margins(Ui.fill(), 0, Ui.dp(this, 10), 0, 0));
        root.addView(buttons, Ui.fill());

        setContentView(root);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tracker.start();
        updateGpsLine();
    }

    @Override
    protected void onPause() {
        super.onPause();
        tracker.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        tracker.shutdown();
    }

    @Override
    public void onLocation(Location location) {
        boolean first = here == null;
        here = location;
        updateGpsLine();
        if (first || list.getChildCount() > 0) render();
    }

    @Override
    public void onAddress(String address) {
    }

    private void updateGpsLine() {
        if (!LocationTracker.hasPermission(this)) {
            gpsLine.setText("Location is not allowed for this app, so sites cannot be detected.");
            gpsLine.setTextColor(Ui.ACCENT);
        } else if (here == null) {
            gpsLine.setText(tracker.isGpsOn() ? "Finding your position…"
                    : "Phone location is switched off.");
            gpsLine.setTextColor(Ui.DIM);
        } else {
            String acc = here.hasAccuracy()
                    ? "  (GPS ±" + Math.round(here.getAccuracy()) + " m)" : "";
            gpsLine.setText("You are at " + Geo.plain(here.getLatitude(), here.getLongitude())
                    + acc);
            gpsLine.setTextColor(Ui.DIM);
        }
    }

    private void render() {
        list.removeAllViews();
        if (projects.isEmpty()) {
            TextView empty = Ui.text(this, "No sites yet.\n\nGo to a site and press the yellow "
                    + "button, or type the coordinates from a map.", 15, Ui.DIM, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 48), 0, 0);
            list.addView(empty, Ui.fill());
            return;
        }
        for (final Project p : projects) {
            LinearLayout card = Ui.column(this);
            card.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 14)));
            int pad = Ui.dp(this, 14);
            card.setPadding(pad, pad, pad, pad);
            card.addView(Ui.text(this, p.name, 17, Ui.TEXT, true));
            String sub = join(p.company, p.place);
            if (!sub.isEmpty()) card.addView(Ui.text(this, sub, 13, Ui.DIM, false));
            card.addView(Ui.text(this, Geo.plain(p.lat, p.lon) + "   ·   circle of "
                    + Math.round(p.radiusM) + " m", 13, Ui.DIM, false),
                    Ui.margins(Ui.fill(), 0, Ui.dp(this, 4), 0, 0));
            if (here != null) {
                double d = Geo.distanceM(here.getLatitude(), here.getLongitude(), p.lat, p.lon);
                boolean on = d <= p.radiusM;
                TextView where = Ui.text(this, on
                        ? "●  You are on this site (" + Geo.distanceLabel(d)
                        + " from its centre)"
                        : Geo.distanceLabel(d) + " from here", 13, on ? Ui.GREEN : Ui.DIM, on);
                card.addView(where, Ui.margins(Ui.fill(), 0, Ui.dp(this, 4), 0, 0));
            }
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    edit(p, null);
                }
            });
            list.addView(card, Ui.margins(Ui.fill(), 0, 0, 0, Ui.dp(this, 10)));
        }
    }

    private static String join(String a, String b) {
        boolean ha = !StampText.blank(a), hb = !StampText.blank(b);
        if (ha && hb) return a.trim() + "  ·  " + b.trim();
        return ha ? a.trim() : hb ? b.trim() : "";
    }

    private void addHere() {
        if (here == null) {
            toast(LocationTracker.hasPermission(this)
                    ? "Still finding your position. Stand in the open for a moment."
                    : "Allow location for this app first.");
            return;
        }
        if (here.hasAccuracy() && here.getAccuracy() > 60) {
            new AlertDialog.Builder(this)
                    .setTitle("GPS is not sharp yet")
                    .setMessage("Your position is only good to about "
                            + Math.round(here.getAccuracy()) + " m. Waiting a little in the "
                            + "open gives a better centre point for the site.")
                    .setPositiveButton("Use it anyway", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            edit(null, here);
                        }
                    })
                    .setNegativeButton("Wait", null)
                    .show();
            return;
        }
        edit(null, here);
    }

    private EditText field(LinearLayout into, String label, String value, String hint,
            int inputType) {
        TextView l = Ui.text(this, label, 12, Ui.DIM, true);
        into.addView(l, Ui.margins(Ui.fill(), 0, Ui.dp(this, 12), 0, 0));
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setTextSize(16);
        into.addView(e, Ui.fill());
        return e;
    }

    /**
     * Opens the window to add or change a site.
     *
     * @param existing the site to change, or null for a new one
     * @param at       where to put a new site, or null to type it
     */
    private void edit(final Project existing, Location at) {
        LinearLayout form = Ui.column(this);
        int pad = Ui.dp(this, 22);
        form.setPadding(pad, 0, pad, 0);

        int caps = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
        final EditText name = field(form, "PROJECT NAME (goes on the stamp)",
                existing == null ? "" : existing.name, "For example ZONAL OFFICE BUILDING", caps);
        final EditText company = field(form, "LINE ABOVE THE NAME (optional)",
                existing == null ? "" : existing.company, "Company or contractor", caps);
        final EditText place = field(form, "LINE BELOW THE NAME (optional)",
                existing == null ? "" : existing.place, "Town or area", caps);
        String coordText = existing != null ? Geo.plain(existing.lat, existing.lon)
                : at != null ? Geo.plain(at.getLatitude(), at.getLongitude()) : "";
        final EditText coords = field(form, "CENTRE POINT (latitude, longitude)", coordText,
                "-2.516400, 32.917500", InputType.TYPE_CLASS_TEXT);
        openCoords = coords;

        TextView useHere = Ui.text(this, "Use where I am now", 14, Ui.ACCENT, true);
        useHere.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        useHere.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (here == null) {
                    toast("Still finding your position.");
                } else {
                    coords.setText(Geo.plain(here.getLatitude(), here.getLongitude()));
                }
            }
        });
        form.addView(useHere);
        TextView onMap = Ui.text(this, "Check this point on a map", 14, Ui.ACCENT, true);
        onMap.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
        onMap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                double[] ll = Geo.parseLatLon(coords.getText().toString());
                if (ll == null) {
                    toast("Type the coordinates first.");
                    return;
                }
                String at = String.format(Locale.US, "%.6f,%.6f", ll[0], ll[1]);
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("geo:" + at + "?q=" + at)));
                } catch (ActivityNotFoundException e) {
                    toast("No map app found on this phone.");
                }
            }
        });
        form.addView(onMap);

        final EditText radius = field(form, "HOW FAR FROM THE CENTRE COUNTS AS ON SITE (metres)",
                String.valueOf(Math.round(existing == null ? 150 : existing.radiusM)), "150",
                InputType.TYPE_CLASS_NUMBER);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "New site" : "Change site")
                .setView(scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null);
        if (existing != null) b.setNeutralButton("Delete", null);
        final AlertDialog dialog = b.create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                if (openCoords == coords) openCoords = null;
            }
        });
        dialog.show();

        // Set here, after show(), so a mistake keeps the window open instead of closing it.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        String n = name.getText().toString().trim();
                        if (n.isEmpty()) {
                            name.setError("Give the project a name");
                            name.requestFocus();
                            return;
                        }
                        double[] ll = Geo.parseLatLon(coords.getText().toString());
                        if (ll == null) {
                            coords.setError("Type it like -2.516400, 32.917500");
                            coords.requestFocus();
                            return;
                        }
                        double r;
                        try {
                            r = Double.parseDouble(radius.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            r = -1;
                        }
                        if (r < 10 || r > 20000) {
                            radius.setError("Between 10 and 20000 metres");
                            radius.requestFocus();
                            return;
                        }
                        Project p = existing != null ? existing : new Project();
                        if (existing == null) {
                            p.id = ProjectStore.newId();
                            projects.add(p);
                        }
                        p.name = n;
                        p.company = company.getText().toString().trim();
                        p.place = place.getText().toString().trim();
                        p.lat = ll[0];
                        p.lon = ll[1];
                        p.radiusM = r;
                        store.save(projects);
                        render();
                        dialog.dismiss();
                        warnIfOverlapping(p);
                    }
                });
        if (existing != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            new AlertDialog.Builder(ProjectsActivity.this)
                                    .setMessage("Delete " + existing.name + "? Photos already "
                                            + "taken are not touched.")
                                    .setPositiveButton("Delete",
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface d, int w) {
                                                    projects.remove(existing);
                                                    store.save(projects);
                                                    render();
                                                    dialog.dismiss();
                                                }
                                            })
                                    .setNegativeButton("Keep", null)
                                    .show();
                        }
                    });
        }
    }

    /** Two circles that overlap still work (the closer centre wins), but it is worth knowing. */
    private void warnIfOverlapping(Project saved) {
        for (Project other : projects) {
            if (other == saved) continue;
            double d = Geo.distanceM(saved.lat, saved.lon, other.lat, other.lon);
            if (d < saved.radiusM + other.radiusM) {
                toast("This circle overlaps " + other.name + ". Where they overlap, the "
                        + "closer centre wins.");
                return;
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
