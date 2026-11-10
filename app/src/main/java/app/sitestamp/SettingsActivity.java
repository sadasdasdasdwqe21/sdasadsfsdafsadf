package app.sitestamp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** What the stamp shows and how it looks, photo and video quality, and the app's theme. */
public final class SettingsActivity extends Activity {

    private Prefs prefs;
    private LinearLayout body;
    private ScrollView scroll;
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
        Ui.applyTheme(this);
        super.onCreate(state);
        Ui.applyBars(this);
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
                Ui.dp(this, 230), Gravity.CENTER_HORIZONTAL));
        root.addView(holder, Ui.fill());

        scroll = new ScrollView(this);
        body = Ui.column(this);
        body.setPadding(pad, 0, pad, pad);
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));
        setContentView(root);
        build();
        if (state != null) {
            final int y = state.getInt("scroll", 0);
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    scroll.scrollTo(0, y);
                }
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        // Keeps the place in the list when the screen is rebuilt for a new theme.
        if (scroll != null) out.putInt("scroll", scroll.getScrollY());
    }

    private void build() {
        final int keepY = scroll.getScrollY();
        body.removeAllViews();

        section("How the stamp looks");
        placeGrid();
        slider("Distance from the edge", Prefs.EDGE, 0, Prefs.EDGE_MAX, 1, prefs.edge(),
                new Labeler() {
                    @Override
                    public String label(int v) {
                        return v == 0 ? "Touching the edge" : v <= 3 ? "Close"
                                : v <= 8 ? "Normal" : v <= 14 ? "Far" : "Very far";
                    }
                });
        slider("Text size", Prefs.SIZE_PCT, Prefs.SIZE_MIN, Prefs.SIZE_MAX, Prefs.SIZE_STEP,
                prefs.sizePct(), new Labeler() {
                    @Override
                    public String label(int v) {
                        return v + "%" + (v == 100 ? "  (normal)" : "");
                    }
                });
        choice("Text colour", Prefs.COLOR, Prefs.COLOR_NAMES, 0);
        choice("Box behind the text", Prefs.BOX_STYLE, Prefs.BOX_NAMES, prefs.boxStyle());

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

        section("Photo and video");
        choice("Photo size", Prefs.PHOTO_SIZE, Prefs.PHOTO_SIZE_NAMES, 0);
        choice("Video quality", Prefs.VIDEO_QUALITY, Prefs.VIDEO_QUALITY_NAMES, 0);
        toggle("Record sound with video", null, Prefs.AUDIO);
        toggle("Shutter sound", null, Prefs.SOUND);

        section("App");
        choice("Theme", Prefs.THEME, Prefs.THEME_NAMES, Ui.THEME_DARK);

        TextView where = Ui.text(this, "Photos and videos are saved in the gallery under "
                + "DCIM/SiteStamp, in one folder per project. Pictures taken away from every "
                + "site go in the folder called Other.", 13, Ui.DIM, false);
        body.addView(where, Ui.margins(Ui.fill(), 0, Ui.dp(this, 22), 0, 0));

        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, keepY);
            }
        });
    }

    private void section(String title) {
        TextView t = Ui.text(this, title.toUpperCase(), 12, Ui.ACCENT_TEXT, true);
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

    private TextView labels(LinearLayout row, String title, String sub) {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, title, 15, Ui.TEXT, false));
        TextView subView = null;
        if (sub != null) {
            subView = Ui.text(this, sub, 12, Ui.DIM, false);
            col.addView(subView);
        }
        row.addView(col, Ui.weight());
        return subView;
    }

    // ---- Where the stamp sits: nine places, picked on a small picture of the photo ----

    private void placeGrid() {
        LinearLayout row = rowBox();
        final TextView sub = labels(row, "Position", Prefs.PLACE_NAMES[prefs.place()]);

        LinearLayout grid = Ui.column(this);
        grid.setBackground(Ui.round(Ui.PANEL_HI, Ui.dp(this, 10)));
        int inset = Ui.dp(this, 4);
        grid.setPadding(inset, inset, inset, inset);
        final View[] cells = new View[9];
        int cw = Ui.dp(this, 40), ch = Ui.dp(this, 30), gap = Ui.dp(this, 3);
        for (int r = 0; r < 3; r++) {
            LinearLayout line = Ui.row(this);
            for (int c = 0; c < 3; c++) {
                final int index = r * 3 + c;
                View cell = new View(this);
                cell.setContentDescription(Prefs.PLACE_NAMES[index]);
                cell.setClickable(true);
                cell.setFocusable(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cw, ch);
                lp.setMargins(gap, gap, gap, gap);
                line.addView(cell, lp);
                cells[index] = cell;
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.setI(Prefs.PLACE9, index);
                        paintCells(cells, index);
                        if (sub != null) sub.setText(Prefs.PLACE_NAMES[index]);
                        sample.invalidate();
                    }
                });
            }
            grid.addView(line);
        }
        paintCells(cells, prefs.place());
        row.addView(grid);
    }

    private void paintCells(View[] cells, int chosen) {
        for (int i = 0; i < cells.length; i++) {
            cells[i].setBackground(Ui.round(i == chosen ? Ui.ACCENT : Ui.PANEL,
                    Ui.dp(this, 6)));
        }
    }

    // ---- A slider with its value written beside the title ----

    private interface Labeler {
        String label(int value);
    }

    private void slider(String title, final String key, final int min, int max, final int step,
            int current, final Labeler labeler) {
        LinearLayout box = Ui.column(this);
        box.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 12)));
        int p = Ui.dp(this, 14);
        box.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 10));
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, title, 15, Ui.TEXT, false), Ui.weight());
        final TextView value = Ui.text(this, labeler.label(current), 14, Ui.ACCENT_TEXT, true);
        head.addView(value);
        box.addView(head, Ui.fill());

        LinearLayout line = Ui.row(this);
        TextView minus = stepButton("−");
        TextView plus = stepButton("+");
        final SeekBar bar = new SeekBar(this);
        bar.setMax((max - min) / step);
        bar.setProgress((current - min) / step);
        line.addView(minus);
        line.addView(bar, Ui.weight());
        line.addView(plus);
        box.addView(line, Ui.margins(Ui.fill(), 0, Ui.dp(this, 6), 0, 0));
        body.addView(box, Ui.margins(Ui.fill(), 0, 0, 0, Ui.dp(this, 6)));

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = min + progress * step;
                value.setText(labeler.label(v));
                prefs.setI(key, v);
                sample.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bar.setProgress(Math.max(0, bar.getProgress() - 1));
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bar.setProgress(Math.min(bar.getMax(), bar.getProgress() + 1));
            }
        });
    }

    private TextView stepButton(String sign) {
        TextView b = Ui.text(this, sign, 20, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(Ui.PANEL_HI, Ui.dp(this, 10)));
        int s = Ui.dp(this, 40);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    // ---- On/off, pick-one and text rows ----

    private void toggle(String title, String sub, final String key) {
        LinearLayout row = rowBox();
        labels(row, title, sub);
        final Switch s = new Switch(this);
        boolean def = Prefs.AUDIO.equals(key) || Prefs.SOUND.equals(key) || Prefs.defaultFor(key);
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
                                d.dismiss();
                                if (asBool) {
                                    prefs.setB(key, which == 1);
                                } else {
                                    prefs.setI(key, which);
                                }
                                if (Prefs.BOX_STYLE.equals(key)) keepTextReadable(which);
                                if (Prefs.THEME.equals(key)) {
                                    // Build the whole screen again in the new colours.
                                    recreate();
                                    return;
                                }
                                build();
                                sample.invalidate();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    /** White text on a light box, or black text on a dark one, cannot be read: swap it. */
    private void keepTextReadable(int boxStyle) {
        int color = prefs.getI(Prefs.COLOR, Prefs.COLOR_WHITE);
        if (boxStyle == StampRenderer.BOX_LIGHT && color == Prefs.COLOR_WHITE) {
            prefs.setI(Prefs.COLOR, Prefs.COLOR_BLACK);
        } else if (boxStyle != StampRenderer.BOX_LIGHT && color == Prefs.COLOR_BLACK) {
            prefs.setI(Prefs.COLOR, Prefs.COLOR_WHITE);
        }
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
