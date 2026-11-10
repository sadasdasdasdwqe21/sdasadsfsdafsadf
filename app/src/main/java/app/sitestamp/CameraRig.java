package app.sitestamp;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import androidx.activity.ComponentActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MirrorMode;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.ZoomState;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.effects.Frame;
import androidx.camera.effects.OverlayEffect;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Everything that talks to the camera.
 *
 * Photo mode: preview plus a full-size still. The stamp is drawn into the still
 * afterwards by {@link PhotoSaver}.
 *
 * Video mode: preview plus a recorder, with an overlay drawn into the camera picture
 * before it reaches either of them. So the stamp, with its running clock, is in every
 * frame of the video, and the preview shows exactly what is being recorded.
 */
final class CameraRig {

    interface Host {
        void onCameraReady(boolean hasFlash, boolean canSwitch, boolean front);

        void onCameraError(String message);
    }

    interface PhotoCallback {
        /** Called on a background thread. Exactly one of jpeg and bitmap is set. */
        void onPicture(byte[] jpeg, Bitmap bitmap, int rotationDegrees, Rect crop);

        void onError(String message);
    }

    interface RecordCallback {
        void onStarted();

        void onProgress(long durationNanos);

        void onFinished(Uri uri, boolean failed, String message);
    }

    /** What the overlay should draw. Swapped in whole from the main thread. */
    static final class Snapshot {
        final List<StampText.Line> lines;
        final StampRenderer.Style style;
        final long version;

        Snapshot(List<StampText.Line> lines, StampRenderer.Style style, long version) {
            this.lines = lines;
            this.style = style;
            this.version = version;
        }
    }

    private final ComponentActivity activity;
    private final PreviewView previewView;
    private final Host host;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final HandlerThread effectThread = new HandlerThread("stamp-overlay");
    private final Handler effectHandler;
    private final StampRenderer effectRenderer = new StampRenderer();

    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private OverlayEffect effect;
    private Recording recording;

    private boolean wantVideo;
    private boolean wantFront;
    private int photoSize;
    private int videoQuality;
    private boolean bound;
    private int surfaceRotation = Surface.ROTATION_0;
    private int flashMode = ImageCapture.FLASH_MODE_OFF;

    // Read on the overlay thread.
    private volatile Snapshot snapshot;
    private volatile int stampRotation;
    private volatile boolean stampFlip;
    private volatile boolean redraw = true;
    // Used on the overlay thread only.
    private long drawnVersion = -1;
    private int drawnRotation = -1;
    private boolean drawnFlip;
    private int drawnW, drawnH;
    private final Rect drawnCrop = new Rect();

    CameraRig(ComponentActivity activity, PreviewView previewView, Host host) {
        this.activity = activity;
        this.previewView = previewView;
        this.host = host;
        effectThread.start();
        effectHandler = new Handler(effectThread.getLooper());
        previewView.setScaleType(PreviewView.ScaleType.FIT_CENTER);
    }

    /** Opens the camera in the given mode. Safe to call again to change mode or lens. */
    void open(boolean video, boolean front, int photoSizeIndex, int videoQualityIndex) {
        wantVideo = video;
        wantFront = front;
        photoSize = photoSizeIndex;
        videoQuality = videoQualityIndex;
        if (provider != null) {
            bind();
            return;
        }
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(activity);
        future.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    provider = future.get();
                } catch (Exception e) {
                    host.onCameraError("The camera could not be started.");
                    return;
                }
                bind();
            }
        }, activity.getMainExecutor());
    }

    boolean isBound() {
        return bound;
    }

    boolean isVideo() {
        return wantVideo;
    }

    boolean isFront() {
        return wantFront;
    }

    boolean isRecording() {
        return recording != null;
    }

    private void bind() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            provider.unbindAll();
            bound = false;
            closeEffect();
            imageCapture = null;
            videoCapture = null;

            boolean hasBack = has(CameraSelector.DEFAULT_BACK_CAMERA);
            boolean hasFront = has(CameraSelector.DEFAULT_FRONT_CAMERA);
            if (!hasBack && !hasFront) {
                host.onCameraError("This phone has no camera the app can use.");
                return;
            }
            if (wantFront && !hasFront) wantFront = false;
            if (!wantFront && !hasBack) wantFront = true;
            CameraSelector selector = wantFront ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA;

            ResolutionSelector previewSize = new ResolutionSelector.Builder()
                    .setAspectRatioStrategy(wantVideo
                            ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                            : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build();
            Preview preview = new Preview.Builder().setResolutionSelector(previewSize).build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            if (!wantVideo) {
                imageCapture = new ImageCapture.Builder()
                        .setResolutionSelector(photoSelector(photoSize))
                        .setFlashMode(flashMode)
                        .setTargetRotation(surfaceRotation)
                        .build();
                camera = provider.bindToLifecycle(activity, selector, preview, imageCapture);
            } else {
                Quality q = videoQuality == 2 ? Quality.SD
                        : videoQuality == 1 ? Quality.HD : Quality.FHD;
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(q,
                                FallbackStrategy.lowerQualityOrHigherThan(q)))
                        .setAspectRatio(AspectRatio.RATIO_16_9)
                        .build();
                VideoCapture.Builder<Recorder> vb = new VideoCapture.Builder<>(recorder);
                // Front camera: mirror the video the same way the preview is mirrored.
                vb.setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY);
                vb.setTargetRotation(surfaceRotation);
                videoCapture = vb.build();

                redraw = true;
                effect = new OverlayEffect(CameraEffect.PREVIEW | CameraEffect.VIDEO_CAPTURE,
                        0, effectHandler, new androidx.core.util.Consumer<Throwable>() {
                            @Override
                            public void accept(Throwable t) {
                                // The picture carries on without the stamp; a rebind restores it.
                            }
                        });
                effect.setOnDrawListener(new androidx.arch.core.util.Function<Frame, Boolean>() {
                    @Override
                    public Boolean apply(Frame frame) {
                        return drawOverlay(frame);
                    }
                });

                // Preview and video keep exactly the same upright 9:16 part of the picture.
                ViewPort viewPort = new ViewPort.Builder(new Rational(9, 16), displayRotation())
                        .setScaleType(ViewPort.FILL_CENTER)
                        .build();
                UseCaseGroup group = new UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(videoCapture)
                        .addEffect(effect)
                        .setViewPort(viewPort)
                        .build();
                camera = provider.bindToLifecycle(activity, selector, group);
            }
            bound = true;
            applyRotation();
            host.onCameraReady(camera.getCameraInfo().hasFlashUnit(), hasBack && hasFront,
                    wantFront);
        } catch (Exception e) {
            bound = false;
            String m = e.getMessage();
            host.onCameraError("The camera could not be opened"
                    + (m == null || m.isEmpty() ? "." : ": " + m));
        }
    }

    private boolean has(CameraSelector selector) {
        try {
            return provider.hasCamera(selector);
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private int displayRotation() {
        try {
            return activity.getWindowManager().getDefaultDisplay().getRotation();
        } catch (RuntimeException e) {
            return Surface.ROTATION_0;
        }
    }

    private static ResolutionSelector photoSelector(int sizeIndex) {
        Size bound = sizeIndex == 2 ? new Size(2048, 1536)
                : sizeIndex == 1 ? new Size(3264, 2448) : new Size(4640, 3480);
        return new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(bound,
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build();
    }

    // ---- Stamp inside the video ----

    void setSnapshot(Snapshot s) {
        snapshot = s;
    }

    /** Runs on the overlay thread for every camera frame in video mode. */
    private Boolean drawOverlay(Frame frame) {
        Snapshot s = snapshot;
        if (s == null) return Boolean.TRUE;
        Size size = frame.getSize();
        Rect crop = frame.getCropRect();
        int rot = stampRotation;
        boolean flip = stampFlip;
        boolean changed = redraw || s.version != drawnVersion || rot != drawnRotation
                || flip != drawnFlip || size.getWidth() != drawnW || size.getHeight() != drawnH
                || !drawnCrop.equals(crop);
        if (changed) {
            redraw = false;
            // The overlay keeps its last drawing, so it is redrawn only when something changed.
            Canvas canvas = frame.getOverlayCanvas();
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            effectRenderer.draw(canvas, size.getWidth(), size.getHeight(), crop, rot, flip,
                    s.lines, s.style);
            drawnVersion = s.version;
            drawnRotation = rot;
            drawnFlip = flip;
            drawnW = size.getWidth();
            drawnH = size.getHeight();
            drawnCrop.set(crop);
        }
        return Boolean.TRUE;
    }

    private void closeEffect() {
        if (effect != null) {
            try {
                effect.clearOnDrawListener();
                effect.close();
            } catch (RuntimeException ignored) {
            }
            effect = null;
        }
    }

    // ---- Which way up ----

    /** @param rotation one of the Surface.ROTATION_ values, from how the phone is held */
    void setDeviceRotation(int rotation) {
        surfaceRotation = rotation;
        applyRotation();
    }

    private void applyRotation() {
        if (!bound || camera == null) return;
        if (imageCapture != null) imageCapture.setTargetRotation(surfaceRotation);
        if (videoCapture != null && recording == null) {
            // A recording keeps the way up it started with.
            if (videoCapture.getTargetRotation() != surfaceRotation) {
                videoCapture.setTargetRotation(surfaceRotation);
            }
            // How far the raw camera picture is turned to stand upright for this hold.
            stampRotation = camera.getCameraInfo().getSensorRotationDegrees(surfaceRotation);
            stampFlip = wantFront;
        }
    }

    // ---- Photo ----

    void setFlashMode(int mode) {
        flashMode = mode;
        if (imageCapture != null) imageCapture.setFlashMode(mode);
    }

    void takePhoto(final PhotoCallback cb) {
        if (!bound || imageCapture == null) {
            cb.onError("The camera is not ready yet.");
            return;
        }
        imageCapture.setTargetRotation(surfaceRotation);
        imageCapture.takePicture(io, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy image) {
                byte[] jpeg = null;
                Bitmap bitmap = null;
                int rotation;
                Rect crop;
                try {
                    rotation = image.getImageInfo().getRotationDegrees();
                    crop = new Rect(image.getCropRect());
                    if (image.getFormat() == ImageFormat.JPEG) {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        jpeg = new byte[buffer.remaining()];
                        buffer.get(jpeg);
                    } else {
                        bitmap = image.toBitmap();
                    }
                } catch (Exception e) {
                    cb.onError("The picture could not be read.");
                    return;
                } finally {
                    image.close();
                }
                cb.onPicture(jpeg, bitmap, rotation, crop);
            }

            @Override
            public void onError(ImageCaptureException e) {
                cb.onError("The photo was not taken. Try again.");
            }
        });
    }

    // ---- Video ----

    void setTorch(boolean on) {
        if (bound && camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(on);
        }
    }

    void startRecording(ContentValues values, Location location, boolean audio,
            final RecordCallback cb) {
        if (!bound || videoCapture == null || recording != null) return;
        applyRotation();
        MediaStoreOutputOptions.Builder ob = new MediaStoreOutputOptions.Builder(
                activity.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values);
        if (location != null) ob.setLocation(location);
        try {
            PendingRecording pending = videoCapture.getOutput()
                    .prepareRecording(activity, ob.build());
            if (audio && activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled();
            }
            recording = pending.start(activity.getMainExecutor(),
                    new androidx.core.util.Consumer<VideoRecordEvent>() {
                        @Override
                        public void accept(VideoRecordEvent event) {
                            if (event instanceof VideoRecordEvent.Start) {
                                cb.onStarted();
                            } else if (event instanceof VideoRecordEvent.Status) {
                                cb.onProgress(event.getRecordingStats()
                                        .getRecordedDurationNanos());
                            } else if (event instanceof VideoRecordEvent.Finalize) {
                                VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) event;
                                recording = null;
                                Uri uri = f.getOutputResults().getOutputUri();
                                int error = f.getError();
                                // These endings still leave a good video file behind.
                                boolean kept = !f.hasError()
                                        || error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                                        || error == VideoRecordEvent.Finalize
                                                .ERROR_FILE_SIZE_LIMIT_REACHED
                                        || error == VideoRecordEvent.Finalize
                                                .ERROR_DURATION_LIMIT_REACHED
                                        || error == VideoRecordEvent.Finalize
                                                .ERROR_INSUFFICIENT_STORAGE;
                                String message = "";
                                if (error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) {
                                    message = "The phone's storage is full. The video stopped.";
                                } else if (!kept) {
                                    message = error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
                                            ? "The video was too short to save."
                                            : "The video could not be saved.";
                                }
                                applyRotation();
                                cb.onFinished(uri, !kept, message);
                            }
                        }
                    });
        } catch (Exception e) {
            recording = null;
            cb.onFinished(null, true, "Recording could not start.");
        }
    }

    void stopRecording() {
        if (recording != null) {
            try {
                recording.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ---- Zoom and focus ----

    /** @return the new zoom, or 0 when the camera is not open */
    float zoomBy(float factor) {
        if (!bound || camera == null) return 0;
        ZoomState z = camera.getCameraInfo().getZoomState().getValue();
        if (z == null) return 0;
        float target = Math.max(z.getMinZoomRatio(),
                Math.min(z.getMaxZoomRatio(), z.getZoomRatio() * factor));
        camera.getCameraControl().setZoomRatio(target);
        return target;
    }

    void focusAt(float x, float y) {
        if (!bound || camera == null) return;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (RuntimeException ignored) {
        }
    }

    void release() {
        stopRecording();
        closeEffect();
        io.shutdown();
        effectThread.quitSafely();
    }
}
