package app.sitestamp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.location.Location;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Size;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.ImageCapture;
import androidx.camera.view.PreviewView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The camera screen. */
public final class MainActivity extends ComponentActivity
        implements CameraRig.Host, LocationTracker.Listener {

    private static final int REQ_START = 1;
    private static final int REQ_AUDIO = 2;
    private static final int REQ_LOCATION = 3;
    private static final String AUTO = "auto";
    private static final String NONE = "none";

    private Prefs prefs;
    private ProjectStore store;
    private List<Project> projects = new ArrayList<>();
    private final SiteMatcher matcher = new SiteMatcher();
    private LocationTracker tracker;
    private CameraRig rig;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MediaActionSound sound;
    private OrientationEventListener orientation;

    private AspectFrame frame;
    private PreviewView previewView;
    private StampOverlayView overlay;
    private View curtain;
    private IconView flashIcon, timerIcon, gridIcon, pinIcon, settingsIcon, switchIcon;
    private TextView chip, modePhoto, modeVideo, recLabel, zoomLabel, countdown;
    private LinearLayout messagePanel;
    private TextView messageText, messageButton;
    private ShutterButton shutter;
    private ImageView thumb;

    private boolean videoMode;
    private boolean front;
    private int flash;          // 0 off, 1 on, 2 auto (photo only)
    private int timerSec;       // 0, 3 or 10
    private boolean grid;
    /** AUTO, NONE or a project id. Chosen by hand for this session only. */
    private String manual = AUTO;
    private SiteMatcher.Result match;
    private Project activeProject;
    private final StampText.Fix fix = new StampText.Fix();
    private StampText.Options options = new StampText.Options();
    private StampRenderer.Style style = new StampRenderer.Style();
    private long stampVersion;
    private int deviceDegrees;
    private boolean hasFlash;
    private boolean canSwitch;
    private boolean cameraReady;
    private boolean busy;
    private int counting;
    private boolean pendingRecordAfterAudio;
    private int openedPhotoSize = -1, openedVideoQuality = -1;
    private Uri lastUri;
    private String lastMime = "image/jpeg";
    private Runnable messageAction;
    /** The theme setting this screen was built with. */
    private int builtTheme;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshStamp();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 1000 - (now % 1000) + 5);
        }
    };

    private final Runnable hideZoom = new Runnable() {
        @Override
        public void run() {
            zoomLabel.setVisibility(View.GONE);
        }
    };

    private final Runnable countStep = new Runnable() {
        @Override
        public void run() {
            if (counting <= 0) return;
            counting--;
            if (counting == 0) {
                countdown.setVisibility(View.GONE);
                takePhoto();
            } else {
                countdown.setText(String.valueOf(counting));
                handler.postDelayed(this, 1000);
            }
        }
    };

    // ------------------------------------------------------------------ set-up

    @Override
    protected void onCreate(Bundle state) {
        builtTheme = Ui.applyTheme(this);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Ui.applyBars(this);

        prefs = new Prefs(this);
        store = new ProjectStore(this);
        videoMode = prefs.getB(Prefs.VIDEO_MODE, false);
        flash = Prefs.clamp(prefs.getI(Prefs.FLASH, 0), 0, 2);
        timerSec = prefs.getI(Prefs.TIMER, 0);
        grid = prefs.getB(Prefs.GRID, false);

        setContentView(buildScreen());
        rig = new CameraRig(this, previewView, this);
        tracker = new LocationTracker(this, this);
        sound = new MediaActionSound();
        try {
            sound.load(MediaActionSound.SHUTTER_CLICK);
            sound.load(MediaActionSound.START_VIDEO_RECORDING);
            sound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        } catch (RuntimeException ignored) {
        }

        orientation = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int snapped = ((o + 45) / 90 * 90) % 360;
                int diff = Math.abs(o - snapped);
                if (diff > 180) diff = 360 - diff;
                if (snapped != deviceDegrees && diff <= 30) setDeviceDegrees(snapped);
            }
        };

        applyMode();
        updateIcons();
        loadLastThumb();

        List<String> ask = new ArrayList<>();
        if (!granted(Manifest.permission.CAMERA)) ask.add(Manifest.permission.CAMERA);
        if (!LocationTracker.hasPermission(this)) {
            ask.add(Manifest.permission.ACCESS_FINE_LOCATION);
            ask.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), REQ_START);
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private View buildScreen() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        // Top row of icons.
        LinearLayout top = Ui.row(this);
        int icon = Ui.dp(this, 48);
        flashIcon = icon(IconView.FLASH, top, icon);
        timerIcon = icon(IconView.TIMER, top, icon);
        gridIcon = icon(IconView.GRID, top, icon);
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        pinIcon = icon(IconView.PIN, top, icon);
        settingsIcon = icon(IconView.SETTINGS, top, icon);
        int side = Ui.dp(this, 6);
        top.setPadding(side, 0, side, 0);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, icon));

        // The stage: the camera picture with things floating on it.
        FrameLayout stage = new FrameLayout(this);
        frame = new AspectFrame(this);
        previewView = new PreviewView(this);
        frame.addView(previewView, match());
        overlay = new StampOverlayView(this);
        frame.addView(overlay, match());
        curtain = new View(this);
        curtain.setBackgroundColor(Color.WHITE);
        curtain.setAlpha(0f);
        frame.addView(curtain, match());
        stage.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        chip = Ui.text(this, "", 13, Ui.PIC_TEXT, true);
        chip.setBackground(Ui.round(0xB3000000, Ui.dp(this, 18)));
        chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        chip.setMaxLines(2);
        chip.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams chipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        int m = Ui.dp(this, 10);
        chipLp.setMargins(m, m, m, m);
        stage.addView(chip, chipLp);

        recLabel = Ui.text(this, "", 15, Color.WHITE, true);
        recLabel.setBackground(Ui.round(0xCCB3261E, Ui.dp(this, 14)));
        recLabel.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        recLabel.setVisibility(View.GONE);
        FrameLayout.LayoutParams recLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        recLp.setMargins(m, Ui.dp(this, 58), m, m);
        stage.addView(recLabel, recLp);

        zoomLabel = Ui.text(this, "", 15, Color.WHITE, true);
        zoomLabel.setBackground(Ui.round(0xB3000000, Ui.dp(this, 14)));
        zoomLabel.setPadding(Ui.dp(this, 12), Ui.dp(this, 5), Ui.dp(this, 12), Ui.dp(this, 5));
        zoomLabel.setVisibility(View.GONE);
        stage.addView(zoomLabel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        countdown = Ui.text(this, "", 96, Color.WHITE, true);
        countdown.setShadowLayer(Ui.dp(this, 6), 0, 0, Color.BLACK);
        countdown.setVisibility(View.GONE);
        stage.addView(countdown, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        messagePanel = Ui.column(this);
        messagePanel.setGravity(Gravity.CENTER_HORIZONTAL);
        messagePanel.setBackground(Ui.round(Ui.PANEL, Ui.dp(this, 16)));
        int pad = Ui.dp(this, 20);
        messagePanel.setPadding(pad, pad, pad, pad);
        messageText = Ui.text(this, "", 15, Ui.TEXT, false);
        messageText.setGravity(Gravity.CENTER);
        messagePanel.addView(messageText, Ui.fill());
        messageButton = Ui.button(this, "", true);
        messagePanel.addView(messageButton, Ui.margins(Ui.fill(), 0, Ui.dp(this, 16), 0, 0));
        messageButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (messageAction != null) messageAction.run();
            }
        });
        messagePanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams msgLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        msgLp.setMargins(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);
        stage.addView(messagePanel, msgLp);

        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f));

        // Bottom: photo or video, then thumbnail, shutter, switch camera.
        LinearLayout modes = Ui.row(this);
        modes.setGravity(Gravity.CENTER);
        modePhoto = modeLabel("PHOTO");
        modeVideo = modeLabel("VIDEO");
        modes.addView(modePhoto);
        modes.addView(modeVideo);
        root.addView(modes, Ui.margins(Ui.fill(), 0, Ui.dp(this, 6), 0, 0));

        FrameLayout bottom = new FrameLayout(this);
        int big = Ui.dp(this, 76);
        shutter = new ShutterButton(this);
        bottom.addView(shutter, new FrameLayout.LayoutParams(big, big, Gravity.CENTER));
        int small = Ui.dp(this, 52);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ui.round(Ui.PANEL_HI, Ui.dp(this, 12)));
        thumb.setClipToOutline(true);
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(small, small,
                Gravity.CENTER_VERTICAL | Gravity.START);
        thumbLp.setMarginStart(Ui.dp(this, 34));
        bottom.addView(thumb, thumbLp);
        switchIcon = new IconView(this, IconView.SWITCH);
        switchIcon.setBackground(Ui.round(Ui.PANEL_HI, small / 2f));
        FrameLayout.LayoutParams swLp = new FrameLayout.LayoutParams(small, small,
                Gravity.CENTER_VERTICAL | Gravity.END);
        swLp.setMarginEnd(Ui.dp(this, 34));
        bottom.addView(switchIcon, swLp);
        root.addView(bottom, Ui.margins(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 92)), 0, 0, 0, Ui.dp(this, 10)));

        wire();
        return root;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private IconView icon(int kind, LinearLayout into, int size) {
        IconView v = new IconView(this, kind);
        into.addView(v, new LinearLayout.LayoutParams(size, size));
        return v;
    }

    private TextView modeLabel(String s) {
        TextView t = Ui.text(this, s, 14, Ui.DIM, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8));
        return t;
    }

    private void wire() {
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onShutter();
            }
        });
        modePhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setVideoMode(false);
            }
        });
        modeVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setVideoMode(true);
            }
        });
        flashIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!hasFlash) {
                    toast("This camera has no flash.");
                    return;
                }
                flash = videoMode ? (flash == 1 ? 0 : 1) : (flash + 1) % 3;
                prefs.setI(Prefs.FLASH, flash);
                applyFlash();
                updateIcons();
            }
        });
        timerIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                timerSec = timerSec == 0 ? 3 : timerSec == 3 ? 10 : 0;
                prefs.setI(Prefs.TIMER, timerSec);
                updateIcons();
                toast(timerSec == 0 ? "Timer off" : "Photo timer: " + timerSec + " seconds");
            }
        });
        gridIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grid = !grid;
                prefs.setB(Prefs.GRID, grid);
                updateIcons();
            }
        });
        pinIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rig.isRecording()) return;
                startActivity(new Intent(MainActivity.this, ProjectsActivity.class));
            }
        });
        settingsIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rig.isRecording()) return;
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        switchIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (rig.isRecording() || !canSwitch) return;
                front = !front;
                openCamera();
            }
        });
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onChip();
            }
        });
        thumb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLast();
            }
        });

        final ScaleGestureDetector pinch = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float z = rig.zoomBy(d.getScaleFactor());
                        if (z > 0) showZoom(z);
                        return true;
                    }
                });
        overlay.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                pinch.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        moved = false;
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        moved = true;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.hypot(e.getX() - downX, e.getY() - downY) > Ui.dp(
                                MainActivity.this, 12)) {
                            moved = true;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (!moved && cameraReady) {
                            rig.focusAt(e.getX(), e.getY());
                            overlay.showFocusRing(e.getX(), e.getY());
                        }
                        break;
                    default:
                        break;
                }
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ life cycle

    @Override
    protected void onResume() {
        super.onResume();
        if (prefs.getI(Prefs.THEME, Ui.THEME_DARK) != builtTheme) {
            // The theme was changed in Settings: build this screen again in the new colours.
            recreate();
            return;
        }
        options = prefs.options();
        style = prefs.style();
        projects = store.load();
        matcher.reset();
        if (!AUTO.equals(manual) && !NONE.equals(manual) && find(manual) == null) manual = AUTO;
        placeChip();
        tracker.start();
        orientation.enable();
        handler.removeCallbacks(tick);
        tick.run();
        if (granted(Manifest.permission.CAMERA)) {
            boolean changed = openedPhotoSize != prefs.getI(Prefs.PHOTO_SIZE, 0)
                    || openedVideoQuality != prefs.getI(Prefs.VIDEO_QUALITY, 0);
            if (changed || !rig.isBound()) openCamera();
        } else {
            showCameraNeeded();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        cancelCountdown();
        if (rig.isRecording()) rig.stopRecording();
        tracker.stop();
        orientation.disable();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        rig.release();
        tracker.shutdown();
        worker.shutdown();
        try {
            sound.release();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQ_AUDIO) {
            if (pendingRecordAfterAudio) {
                pendingRecordAfterAudio = false;
                if (!granted(Manifest.permission.RECORD_AUDIO)) {
                    toast("Recording without sound.");
                }
                beginRecording();
            }
            return;
        }
        if (LocationTracker.hasPermission(this)) tracker.start();
        if (granted(Manifest.permission.CAMERA)) {
            if (!rig.isBound()) openCamera();
        } else {
            showCameraNeeded();
        }
        refreshStamp();
    }

    private void showCameraNeeded() {
        showMessage("SiteStamp needs the camera to take photos and videos.", "Allow camera",
                new Runnable() {
                    @Override
                    public void run() {
                        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                            requestPermissions(new String[] {Manifest.permission.CAMERA},
                                    REQ_START);
                        } else {
                            openAppSettings();
                        }
                    }
                });
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private void showMessage(String text, String button, Runnable action) {
        messageText.setText(text);
        messageButton.setText(button);
        messageAction = action;
        messagePanel.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ camera

    private void openCamera() {
        if (!granted(Manifest.permission.CAMERA)) return;
        cameraReady = false;
        openedPhotoSize = prefs.getI(Prefs.PHOTO_SIZE, 0);
        openedVideoQuality = prefs.getI(Prefs.VIDEO_QUALITY, 0);
        rig.setFlashMode(photoFlashMode());
        rig.setDeviceRotation(surfaceFor(deviceDegrees));
        rig.open(videoMode, front, openedPhotoSize, openedVideoQuality);
    }

    @Override
    public void onCameraReady(boolean flashUnit, boolean switchable, boolean isFront) {
        cameraReady = true;
        hasFlash = flashUnit;
        canSwitch = switchable;
        front = isFront;
        messagePanel.setVisibility(View.GONE);
        rig.setDeviceRotation(surfaceFor(deviceDegrees));
        applyFlash();
        updateIcons();
        refreshStamp();
    }

    @Override
    public void onCameraError(String message) {
        cameraReady = false;
        showMessage(message, "Try again", new Runnable() {
            @Override
            public void run() {
                messagePanel.setVisibility(View.GONE);
                openCamera();
            }
        });
    }

    private int photoFlashMode() {
        return flash == 1 ? ImageCapture.FLASH_MODE_ON
                : flash == 2 ? ImageCapture.FLASH_MODE_AUTO : ImageCapture.FLASH_MODE_OFF;
    }

    private void applyFlash() {
        if (videoMode) {
            if (flash == 2) flash = 0;
            rig.setTorch(flash == 1);
        } else {
            rig.setFlashMode(photoFlashMode());
        }
    }

    private void setVideoMode(boolean video) {
        if (video == videoMode || rig.isRecording() || busy) return;
        cancelCountdown();
        videoMode = video;
        prefs.setB(Prefs.VIDEO_MODE, video);
        if (video && flash == 2) flash = 0;
        applyMode();
        updateIcons();
        openCamera();
    }

    private void applyMode() {
        frame.setRatio(videoMode ? 9f / 16f : 3f / 4f);
        overlay.setShowStamp(!videoMode);
        shutter.setMode(videoMode ? ShutterButton.VIDEO : ShutterButton.PHOTO);
        modePhoto.setTextColor(videoMode ? Ui.DIM : Ui.ACCENT_TEXT);
        modeVideo.setTextColor(videoMode ? Ui.ACCENT_TEXT : Ui.DIM);
    }

    private void updateIcons() {
        flashIcon.setState(flash != 0, flash == 0, flash == 2 ? "A" : "");
        timerIcon.setState(timerSec != 0 && !videoMode, false,
                timerSec != 0 && !videoMode ? String.valueOf(timerSec) : "");
        timerIcon.setEnabled(!videoMode);
        gridIcon.setState(grid, false, "");
        overlay.setShowGrid(grid);
        boolean rec = rig != null && rig.isRecording();
        pinIcon.setEnabled(!rec);
        settingsIcon.setEnabled(!rec);
        switchIcon.setEnabled(!rec && canSwitch);
        pinIcon.invalidate();
        settingsIcon.invalidate();
        switchIcon.invalidate();
        timerIcon.invalidate();
    }

    private static int surfaceFor(int degrees) {
        switch (degrees) {
            case 90:
                return Surface.ROTATION_270;
            case 180:
                return Surface.ROTATION_180;
            case 270:
                return Surface.ROTATION_90;
            default:
                return Surface.ROTATION_0;
        }
    }

    private void setDeviceDegrees(int degrees) {
        deviceDegrees = degrees;
        rig.setDeviceRotation(surfaceFor(degrees));
        float turn = degrees == 270 ? 90 : -degrees;
        View[] turning = {flashIcon, timerIcon, gridIcon, pinIcon, settingsIcon, switchIcon,
                thumb, countdown, zoomLabel};
        for (View v : turning) v.animate().rotation(turn).setDuration(180).start();
        refreshStamp();
    }

    private void showZoom(float ratio) {
        zoomLabel.setText(String.format(Locale.US, "%.1fx", ratio));
        zoomLabel.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideZoom);
        handler.postDelayed(hideZoom, 1200);
    }

    // ------------------------------------------------------------------ position and project

    @Override
    public void onLocation(Location l) {
        fix.has = true;
        fix.lat = l.getLatitude();
        fix.lon = l.getLongitude();
        fix.hasAlt = l.hasAltitude();
        fix.alt = l.getAltitude();
        fix.hasAcc = l.hasAccuracy();
        fix.acc = l.getAccuracy();
        refreshStamp();
    }

    @Override
    public void onAddress(String address) {
        fix.address = address == null ? "" : address;
        refreshStamp();
    }

    private Project find(String id) {
        for (Project p : projects) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** Works out the project, rebuilds the stamp, and hands it to the preview and the video. */
    private void refreshStamp() {
        if (overlay == null) return;
        match = fix.has ? matcher.update(projects, fix.lat, fix.lon) : null;
        if (AUTO.equals(manual)) {
            activeProject = match == null ? null : match.project;
        } else if (NONE.equals(manual)) {
            activeProject = null;
        } else {
            activeProject = find(manual);
        }
        List<StampText.Line> lines = StampText.build(options, activeProject, fix,
                System.currentTimeMillis(), TimeZone.getDefault());
        overlay.setStamp(lines, style, deviceDegrees);
        rig.setSnapshot(new CameraRig.Snapshot(lines, style, ++stampVersion));
        updateChip();
    }

    private void updateChip() {
        String text;
        int color = Ui.PIC_TEXT;
        if (!LocationTracker.hasPermission(this)) {
            text = "Location is off. Tap to allow it";
            color = Ui.ACCENT;
        } else if (!AUTO.equals(manual)) {
            text = (activeProject == null ? "No project name" : activeProject.name)
                    + "  ·  set by hand";
            color = Ui.ACCENT;
        } else if (projects.isEmpty()) {
            text = "Tap to add your project sites";
        } else if (!fix.has) {
            text = tracker.isGpsOn() ? "Finding your position…"
                    : "Phone location is switched off";
            color = Ui.PIC_DIM;
        } else if (match != null && match.project != null) {
            text = "●  " + match.project.name + "  ·  on site";
            color = Ui.PIC_GREEN;
        } else if (match != null && match.nearest != null) {
            text = "Not on a site  ·  " + match.nearest.name + " is "
                    + Geo.distanceLabel(match.nearestM) + " away";
        } else {
            text = "Not on a site";
        }
        chip.setText(text);
        chip.setTextColor(color);
    }

    /** Keeps the chip away from the corner the stamp is in. */
    private void placeChip() {
        boolean stampOnTop = style.v == StampRenderer.START;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) chip.getLayoutParams();
        lp.gravity = (stampOnTop ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        chip.setLayoutParams(lp);
        FrameLayout.LayoutParams rp = (FrameLayout.LayoutParams) recLabel.getLayoutParams();
        rp.gravity = lp.gravity;
        int m = Ui.dp(this, 10);
        rp.setMargins(m, stampOnTop ? m : Ui.dp(this, 58), m, stampOnTop ? Ui.dp(this, 58) : m);
        recLabel.setLayoutParams(rp);
    }

    private void onChip() {
        if (rig.isRecording()) return;
        if (!LocationTracker.hasPermission(this)) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                    || !prefs.getB("asked_location", false)) {
                prefs.setB("asked_location", true);
                requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            } else {
                openAppSettings();
            }
            return;
        }
        if (projects.isEmpty()) {
            startActivity(new Intent(this, ProjectsActivity.class));
            return;
        }
        final List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ids.add(AUTO);
        labels.add("Automatic: GPS picks the project");
        for (Project p : projects) {
            ids.add(p.id);
            String d = fix.has ? "  (" + Geo.distanceLabel(
                    Geo.distanceM(fix.lat, fix.lon, p.lat, p.lon)) + ")" : "";
            labels.add(p.name + d);
        }
        ids.add(NONE);
        labels.add("No project name");
        ids.add("");
        labels.add("Add or edit sites…");
        int checked = Math.max(0, ids.indexOf(manual));
        new AlertDialog.Builder(this)
                .setTitle("Project on the stamp")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                                String id = ids.get(which);
                                if (id.isEmpty()) {
                                    startActivity(new Intent(MainActivity.this,
                                            ProjectsActivity.class));
                                    return;
                                }
                                manual = id;
                                refreshStamp();
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ shutter

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getRepeatCount() == 0) onShutter();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void onShutter() {
        if (!cameraReady) return;
        if (videoMode) {
            if (rig.isRecording()) {
                rig.stopRecording();
            } else {
                startRecording();
            }
            return;
        }
        if (counting > 0) {
            cancelCountdown();
            return;
        }
        if (busy) return;
        if (timerSec > 0) {
            counting = timerSec;
            countdown.setText(String.valueOf(counting));
            countdown.setVisibility(View.VISIBLE);
            handler.postDelayed(countStep, 1000);
        } else {
            takePhoto();
        }
    }

    private void cancelCountdown() {
        counting = 0;
        handler.removeCallbacks(countStep);
        if (countdown != null) countdown.setVisibility(View.GONE);
    }

    private void takePhoto() {
        if (busy || !cameraReady) return;
        busy = true;
        final long time = System.currentTimeMillis();
        final Project project = activeProject == null ? null : activeProject.copy();
        final StampText.Fix shotFix = copy(fix);
        final StampRenderer.Style shotStyle = style;
        final List<StampText.Line> lines = StampText.build(options, project, shotFix, time,
                TimeZone.getDefault());
        if (prefs.getB(Prefs.SOUND, true)) {
            try {
                sound.play(MediaActionSound.SHUTTER_CLICK);
            } catch (RuntimeException ignored) {
            }
        }
        curtain.setAlpha(0.6f);
        curtain.animate().alpha(0f).setDuration(220).start();

        rig.takePhoto(new CameraRig.PhotoCallback() {
            @Override
            public void onPicture(byte[] jpeg, Bitmap bitmap, int rotation, Rect crop) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                    }
                });
                PhotoSaver.Request r = new PhotoSaver.Request();
                r.jpeg = jpeg;
                r.bitmap = bitmap;
                r.rotation = rotation;
                r.crop = crop;
                r.lines = lines;
                r.style = shotStyle;
                r.fix = shotFix;
                r.project = project;
                r.timeMs = time;
                try {
                    final Uri uri = PhotoSaver.save(getApplicationContext(), r);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onSaved(uri, "image/jpeg");
                        }
                    });
                } catch (Exception | OutOfMemoryError e) {
                    final String m = e instanceof OutOfMemoryError
                            ? "Not enough memory for this photo. Choose a smaller photo size "
                            + "in Settings."
                            : "The photo could not be saved.";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            toast(m);
                        }
                    });
                }
            }

            @Override
            public void onError(final String message) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        busy = false;
                        toast(message);
                    }
                });
            }
        });
    }

    private static StampText.Fix copy(StampText.Fix f) {
        StampText.Fix c = new StampText.Fix();
        c.has = f.has;
        c.lat = f.lat;
        c.lon = f.lon;
        c.hasAlt = f.hasAlt;
        c.alt = f.alt;
        c.hasAcc = f.hasAcc;
        c.acc = f.acc;
        c.address = f.address;
        return c;
    }

    private void startRecording() {
        if (prefs.getB(Prefs.AUDIO, true) && !granted(Manifest.permission.RECORD_AUDIO)) {
            pendingRecordAfterAudio = true;
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        beginRecording();
    }

    private void beginRecording() {
        if (!cameraReady || !videoMode || rig.isRecording()) return;
        long time = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                Names.file(activeProject, time, TimeZone.getDefault(), "mp4"));
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, PhotoSaver.relativePath(activeProject));
        if (prefs.getB(Prefs.SOUND, true)) {
            try {
                sound.play(MediaActionSound.START_VIDEO_RECORDING);
            } catch (RuntimeException ignored) {
            }
        }
        rig.startRecording(values, tracker.last(), prefs.getB(Prefs.AUDIO, true),
                new CameraRig.RecordCallback() {
                    @Override
                    public void onStarted() {
                        shutter.setMode(ShutterButton.RECORDING);
                        recLabel.setText("●  00:00");
                        recLabel.setVisibility(View.VISIBLE);
                        modePhoto.setEnabled(false);
                        modeVideo.setEnabled(false);
                        updateIcons();
                    }

                    @Override
                    public void onProgress(long nanos) {
                        long s = nanos / 1000000000L;
                        recLabel.setText(String.format(Locale.US, "●  %02d:%02d",
                                s / 60, s % 60));
                    }

                    @Override
                    public void onFinished(Uri uri, boolean failed, String message) {
                        shutter.setMode(videoMode ? ShutterButton.VIDEO : ShutterButton.PHOTO);
                        recLabel.setVisibility(View.GONE);
                        modePhoto.setEnabled(true);
                        modeVideo.setEnabled(true);
                        updateIcons();
                        if (prefs.getB(Prefs.SOUND, true)) {
                            try {
                                sound.play(MediaActionSound.STOP_VIDEO_RECORDING);
                            } catch (RuntimeException ignored) {
                            }
                        }
                        if (!failed && uri != null && !Uri.EMPTY.equals(uri)) {
                            onSaved(uri, "video/mp4");
                        }
                        if (message != null && !message.isEmpty()) toast(message);
                    }
                });
    }

    // ------------------------------------------------------------------ after saving

    private void onSaved(Uri uri, String mime) {
        lastUri = uri;
        lastMime = mime;
        loadThumb(uri);
    }

    private void loadThumb(final Uri uri) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap b = getContentResolver().loadThumbnail(uri, new Size(256, 256),
                            null);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            thumb.setImageBitmap(b);
                        }
                    });
                } catch (Exception ignored) {
                    // No thumbnail yet. The button still opens the file.
                }
            }
        });
    }

    /** Shows the newest SiteStamp photo on the thumbnail button when the app opens. */
    private void loadLastThumb() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Uri collection = MediaStore.Images.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY);
                String[] cols = {MediaStore.MediaColumns._ID};
                String where = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
                String[] args = {"%" + PhotoSaver.ALBUM + "/%"};
                try (Cursor c = getContentResolver().query(collection, cols, where, args,
                        MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                    if (c != null && c.moveToFirst()) {
                        final Uri uri = ContentUris.withAppendedId(collection, c.getLong(0));
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (lastUri == null) onSaved(uri, "image/jpeg");
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void openLast() {
        if (lastUri == null) {
            toast("Your photos are saved in the gallery, in DCIM/SiteStamp.");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(lastUri, lastMime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            toast("No app on this phone can open it. Look in the gallery, in DCIM/SiteStamp.");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
