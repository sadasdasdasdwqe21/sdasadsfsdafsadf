package app.sitestamp;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns the camera's picture upright, draws the stamp into it, and saves it in the
 * gallery under DCIM/SiteStamp/(project name). The position, time and project name
 * are also written into the photo's hidden details, so other apps can read them.
 */
final class PhotoSaver {
    private PhotoSaver() {}

    static final String ALBUM = "SiteStamp";
    private static final AtomicInteger COUNTER = new AtomicInteger();

    static final class Request {
        /** The camera's JPEG, or null when {@link #bitmap} is given instead. */
        byte[] jpeg;
        Bitmap bitmap;
        /** Degrees to turn the picture clockwise so it stands upright. */
        int rotation;
        /** The part of the picture to keep, or null for all of it. */
        Rect crop;
        List<StampText.Line> lines;
        StampRenderer.Style style;
        StampText.Fix fix;
        Project project;
        long timeMs;
    }

    static String relativePath(Project p) {
        return Environment.DIRECTORY_DCIM + "/" + ALBUM + "/" + Names.folder(p);
    }

    static Uri save(Context context, Request r) throws IOException {
        Bitmap out;
        try {
            out = render(r, 1);
        } catch (OutOfMemoryError e) {
            // Not enough memory for the full picture: keep the shot at half size.
            out = render(r, 2);
        }
        File tmp = new File(context.getCacheDir(),
                "shot_" + System.currentTimeMillis() + "_" + COUNTER.incrementAndGet() + ".jpg");
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                if (!out.compress(Bitmap.CompressFormat.JPEG, 93, fos)) {
                    throw new IOException("Could not write the photo");
                }
            } finally {
                out.recycle();
            }
            writeDetails(tmp, r);

            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    Names.file(r.project, r.timeMs, TimeZone.getDefault(), "jpg"));
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(r.project));
            v.put(MediaStore.MediaColumns.DATE_TAKEN, r.timeMs);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY);
            uri = resolver.insert(collection, v);
            if (uri == null) throw new IOException("The gallery did not accept the photo");
            try (OutputStream os = resolver.openOutputStream(uri);
                    InputStream in = new FileInputStream(tmp)) {
                if (os == null) throw new IOException("Could not open the gallery file");
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        } catch (IOException | RuntimeException e) {
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                }
            }
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage() == null ? "Could not save the photo"
                    : e.getMessage(), e);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static Bitmap render(Request r, int sample) throws IOException {
        Bitmap src;
        if (r.bitmap != null) {
            src = r.bitmap;
        } else {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            o.inSampleSize = sample;
            src = BitmapFactory.decodeByteArray(r.jpeg, 0, r.jpeg.length, o);
        }
        if (src == null) throw new IOException("The camera returned a picture that cannot be read");

        Rect crop = new Rect(0, 0, src.getWidth(), src.getHeight());
        if (r.crop != null) {
            Rect c = new Rect(r.crop.left / sample, r.crop.top / sample,
                    r.crop.right / sample, r.crop.bottom / sample);
            if (c.width() > 16 && c.height() > 16 && crop.contains(c)) crop = c;
        }
        int rot = ((r.rotation % 360) + 360) % 360;
        rot = ((rot + 45) / 90) * 90 % 360;
        boolean whole = crop.width() == src.getWidth() && crop.height() == src.getHeight();
        StampRenderer renderer = new StampRenderer();

        if (rot == 0 && whole && src.isMutable()) {
            renderer.draw(new Canvas(src), src.getWidth(), src.getHeight(), null, 0, false,
                    r.lines, r.style);
            return src;
        }

        int cw = crop.width(), ch = crop.height();
        boolean swap = rot == 90 || rot == 270;
        int ow = swap ? ch : cw, oh = swap ? cw : ch;
        Bitmap out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Matrix m = new Matrix();
        m.postTranslate(-crop.left, -crop.top);
        m.postRotate(rot);
        if (rot == 90) {
            m.postTranslate(ch, 0);
        } else if (rot == 180) {
            m.postTranslate(cw, ch);
        } else if (rot == 270) {
            m.postTranslate(0, cw);
        }
        canvas.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        renderer.draw(canvas, ow, oh, null, 0, false, r.lines, r.style);
        return out;
    }

    private static void writeDetails(File file, Request r) {
        try {
            ExifInterface ex = new ExifInterface(file.getAbsolutePath());
            SimpleDateFormat f = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US);
            String when = f.format(new Date(r.timeMs));
            ex.setAttribute(ExifInterface.TAG_DATETIME, when);
            ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, when);
            ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, when);
            ex.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
            ex.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
            ex.setAttribute(ExifInterface.TAG_SOFTWARE, "SiteStamp");
            if (r.project != null && !StampText.blank(r.project.name)) {
                ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, r.project.name.trim());
            }
            if (r.fix != null && r.fix.has) {
                ex.setLatLong(r.fix.lat, r.fix.lon);
                if (r.fix.hasAlt) ex.setAltitude(r.fix.alt);
            }
            ex.saveAttributes();
        } catch (IOException | RuntimeException ignored) {
            // The photo is still good without the hidden details.
        }
    }
}
