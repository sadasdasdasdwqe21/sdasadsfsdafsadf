package app.shakefinder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets another app read one saved file when the user shares it, and nothing
 * else: read-only, only files in the app's "saved" folder, and only for the
 * app the user picked in the share sheet.
 */
public class ShareProvider extends ContentProvider {

    static final String AUTHORITY = "app.shakefinder.files";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
    }

    static String mime(String name) {
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".wav")) return "audio/x-wav";
        if (name.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    /** The saved file a link points at, or null. Names with anything but letters, digits, _ - . are refused. */
    private File resolve(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || getContext() == null || !name.matches("[A-Za-z0-9_.-]+") || name.contains("..")) return null;
        File f = new File(Store.dir(getContext()), name);
        return f.isFile() ? f : null;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f = resolve(uri);
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        if (f == null) return c;
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name == null ? null : mime(name);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !"r".equals(mode)) throw new FileNotFoundException("not available");
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
