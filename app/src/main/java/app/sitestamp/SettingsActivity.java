package app.sitestamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** What the stamp shows and how it looks, plus photo and video quality. */
public final class SettingsActivity extends Activity {

    private Prefs prefs;
    private LinearLayout body;
    private Sample sample;

    /** A small picture at the top that shows the stamp with the current choices. */
    private final class Sample extends View {
        private final StampRenderer renderer = new StampRenderer();
        private final Paint sky = new Paint();

        Sample() {
            super(SettingsActivity.this);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            sky.setShader(new LinearGradient(0, 0, 0, h, 0xFF4A6B8A, 0xFF8A7A62,
                    Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRect(0, 0, getWidth(), getHeight(), sky);
            Project p = new Project();
            p.name = "PROJECT NAME";
            p.place = "TOWN OR AREA";
            StampText.Fix f = new StampText.Fix();
            f.has = true;
            f.lat = -2.5164;
            f.lon = 32.9175;
            f.hasAlt = true;
            f.alt = 1140;
            f.hasAcc = true;
            f.acc = 4;
            f.address = "Street, Area, Town";
            StampText.Options o = prefs.options();
            if (StampText.blank(o.defaultCompany)) o.defaultCompany = "COMPANY NAME";
            if (StampText.blank(o.note)) o.note = "Your note";
            List<StampText.Line> lines = StampText.build(o, p, f, System.currentTimeMillis(),
                    TimeZone.getDefault());
            renderer.draw(c, getWidth(), getHeight(), null, 0, false, lines, prefs.style());
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = new Prefs(this);

        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 18);

        TextView title = Ui.text(this, "Settings", 24, Ui.TEXT, true);
        title.setPadding(pad, pad, pad, Ui.dp(this, 10));
        root.addView(title, Ui.fill());

        AspectFrame box = new AspectFrame(this);
        box.setRatio(4f / 3f);
        sample = new Sample();
        box.addView(sample, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(pad, 0, pad, 0);
        holder.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 230)));
        root.addView(holder, Ui.fill());

        ScrollView scroll = new ScrollView(this);
        body = Ui.column(this);
        body.setPadding(pad, 0, pad, pad);
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));
        setContentView(root);
        build();
    }

    private void build() {
        body.removeAllViews();

        section("What the stamp shows");
        toggle("Company line", "The top line. Each site can have its own.", Prefs.SHOW_COMPANY);
        text("Company name when the site has none", Prefs.COMPANY, "Not set",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        toggle("Project name", "Filled in by GPS when you are on a saved site.",
                Prefs.SHOW_PROJECT);
        toggle("Line below the project name", "The town or area saved with the site.",
                Prefs.SHOW_PLACE);
        toggle("Date and time", null, Prefs.SHOW_DATE);
        List<String> dates = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < StampText.DATE_PATTERNS.length; i++) {
            dates.add(StampText.date(i, now, TimeZone.getDefault()));
        }
        choice("Date and time style", Prefs.DATE_PATTERN, dates.toArray(new String[0]), 0);
        toggle("Address", "Street and area. Needs internet; left out when there is none.",
                Prefs.SHOW_ADDRESS);
        toggle("GPS coordinates", null, Prefs.SHOW_COORDS);
        choice("Coordinate style", Prefs.DMS,
                new String[] {"Decimal: 2.51640°S  32.91750°E",
                        "Degrees, minutes, seconds: 2°30'59.0\"S"}, -1);
        toggle("Altitude", null, Prefs.SHOW_ALTITUDE);
        toggle("GPS accuracy", null, Prefs.SHOW_ACCURACY);
        toggle("A note of your own", "For example the activity or the block.", Prefs.SHOW_NOTE);
        text("Note text", Prefs.NOTE, "Not set", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        section("How the stamp looks");
        choice("Corner", Prefs.POSITION, Prefs.POSITION_NAMES, 0);
        choice("Text size", Prefs.SIZE, Prefs.SIZE_NAMES, 1);
        choice("Text colour", Prefs.COLOR, Prefs.COLOR_NAMES, 0);
        toggle("Dark box behind the text", "Keeps the text readable on bright pictures.",
                Prefs.BOX);

        section("Photo and video");
        choice("Photo size", Prefs.PHOTO_SIZE, Prefs.PHOTO_SIZE_NAMES, 0);
        choice("Video quality", Prefs.VIDEO_QUALITY, Prefs.VIDEO_QUALITY_NAMES, 0);
        toggle("Record sound with video", null, Prefs.AUDIO);
        toggle("Shutter sound", null, Prefs.SOUND);

        TextView where = Ui.text(this, "Photos and videos are saved in the gallery under "
                + "DCIM/SiteStamp, in one folder per project. Pictures taken away from every "
                + "site go in the folder called Other.", 13, Ui.DIM, false);
        body.addView(where, Ui.margins(Ui.fill(), 0, Ui.dp(this, 22), 0, 0));
    }

    private void section(String title) {
        TextView t = Ui.text(this, title.toUpperCase(), 12, Ui.ACCENT, true);
        t.setLetterSpacing(0.06f);
        body.addView(t, Ui.margins(Ui.fill(), 0, Ui.dp(this, 24), 0, Ui.dp(this, 6)));
    }

    private LinearLayout rowBox() {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 12)));
        int p = Ui.dp(this, 14);
        row.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        row.setMinimumHeight(Ui.dp(this, 56));
        body.addView(row, Ui.margins(Ui.fill(), 0, 0, 0, Ui.dp(this, 6)));
        return row;
    }

    private LinearLayout labels(LinearLayout row, String title, String sub) {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, title, 15, Ui.TEXT, false));
        if (sub != null) col.addView(Ui.text(this, sub, 12, Ui.DIM, false));
        row.addView(col, Ui.weight());
        return col;
    }

    private void toggle(String title, String sub, final String key) {
        LinearLayout row = rowBox();
        labels(row, title, sub);
        final Switch s = new Switch(this);
        boolean def = Prefs.BOX.equals(key) || Prefs.AUDIO.equals(key) || Prefs.SOUND.equals(key)
                || Prefs.defaultFor(key);
        s.setChecked(prefs.getB(key, def));
        s.setClickable(false);
        row.addView(s);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !s.isChecked();
                s.setChecked(on);
                prefs.setB(key, on);
                sample.invalidate();
            }
        });
    }

    /** @param def default index, or -1 when the setting is stored as a yes/no */
    private void choice(final String title, final String key, final String[] names,
            final int def) {
        LinearLayout row = rowBox();
        final boolean asBool = def < 0;
        int current = asBool ? (prefs.getB(key, false) ? 1 : 0)
                : Prefs.clamp(prefs.getI(key, def), 0, names.length - 1);
        labels(row, title, names[current]);
        final int shown = current;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(title)
                        .setSingleChoiceItems(names, shown, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (asBool) {
                                    prefs.setB(key, which == 1);
                                } else {
                                    prefs.setI(key, which);
                                }
                                d.dismiss();
                                build();
                                sample.invalidate();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    private void text(final String title, final String key, String emptyLabel,
            final int inputType) {
        LinearLayout row = rowBox();
        final String current = prefs.getS(key, "");
        labels(row, title, StampText.blank(current) ? emptyLabel : current);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText e = new EditText(SettingsActivity.this);
                e.setText(current);
                e.setInputType(inputType);
                e.setSingleLine(true);
                e.setSelection(e.getText().length());
                FrameLayout wrap = new FrameLayout(SettingsActivity.this);
                int p = Ui.dp(SettingsActivity.this, 22);
                wrap.setPadding(p, Ui.dp(SettingsActivity.this, 8), p, 0);
                wrap.addView(e);
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(title)
                        .setView(wrap)
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                prefs.setS(key, e.getText().toString().trim());
                                build();
                                sample.invalidate();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }
}
