package ai.crystalgram.holo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.LruCache;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.UUID;

/** Exposes a selected document to the chosen viewer through Android's temporary read grant. */
public final class DocumentProvider extends ContentProvider {
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".documents";
    private static final long LIFETIME_MS = 30 * 60 * 1000L;
    private static final LruCache<String, Entry> entries = new LruCache<>(64);

    private static final class Entry {
        final File file;
        final String name;
        final String mime;
        final long expires = SystemClock.elapsedRealtime() + LIFETIME_MS;

        Entry(File file, String name, String mime) {
            this.file = file;
            this.name = name;
            this.mime = mime;
        }
    }

    static Uri offer(File file, String name, String mime) {
        String token = UUID.randomUUID().toString();
        entries.put(token, new Entry(file, name, mime));
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(token).build();
    }

    static void revokeOffers() { entries.evictAll(); }

    private static Entry entry(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 1 || !AUTHORITY.equals(uri.getAuthority()) || Td.termsBlocked()) return null;
        String token = segments.get(0);
        Entry result = entries.get(token);
        if (result != null && result.expires <= SystemClock.elapsedRealtime()) {
            entries.remove(token);
            return null;
        }
        return result;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        Entry result = entry(uri);
        return result == null ? null : result.mime;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String[] columns = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        Entry result = entry(uri);
        if (result == null) return cursor;
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(result.name);
            else if (OpenableColumns.SIZE.equals(column)) row.add(result.file.length());
            else row.add(null);
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Entry result = entry(uri);
        if (result == null || !"r".equals(mode) || !result.file.isFile()) {
            throw new FileNotFoundException("Document unavailable");
        }
        return ParcelFileDescriptor.open(result.file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }
}
