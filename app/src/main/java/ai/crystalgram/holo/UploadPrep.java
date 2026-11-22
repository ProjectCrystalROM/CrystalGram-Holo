package ai.crystalgram.holo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Prepares a picked file for upload.
 *
 * On this device the network is the constraint, not the CPU. The test phone has
 * LTE stripped out and runs EVDO Rev. A, roughly 100-150 kbps up, so a 2.5 MB
 * camera photo is a two to three minute upload - long enough that the radio
 * drops or the user gives up. Spending three seconds of CPU to downscale it to
 * ~250 KB is an easy trade here, and the opposite of the right answer on a
 * modern phone.
 *
 * So: photos are re-encoded small when the link is slow, and sent as-is when it
 * is not. Everything else (video, documents) is passed through untouched -
 * re-encoding video on a Cortex-A7 would take longer than the upload it saves.
 */
final class UploadPrep {

    private static final String TAG = "CG-Upload";

    /** Longest edge for a photo sent over a slow link. */
    static final int MOBILE_MAX_PX = 1280;
    /** JPEG quality for a photo sent over a slow link. */
    static final int MOBILE_QUALITY = 80;

    private UploadPrep() {}

    /** What kind of thing the user picked, decided from the MIME type. */
    enum Kind { PHOTO, VIDEO, DOCUMENT }

    static Kind kindOf(String mime) {
        if (mime == null) return Kind.DOCUMENT;
        if (mime.startsWith("image/")) return Kind.PHOTO;
        if (mime.startsWith("video/")) return Kind.VIDEO;
        return Kind.DOCUMENT;
    }

    /**
     * Resolves a content URI to a real path the app can open, or null.
     *
     * Gallery and the camera hand back a content:// URI that is a MediaStore
     * row, not a file. When that row has a backing file we can use it directly
     * and skip the copy entirely - on a phone with a small data partition,
     * duplicating every sent photo is a waste worth avoiding.
     *
     * The path is verified by opening it, because on this era of Android the
     * MediaStore DATA column can name a mount view (/mnt/sdcard/...) that the
     * app process cannot actually open. A path that does not open would fail at
     * upload time, which is a much worse failure than at pick time.
     */
    static File resolvePath(Context ctx, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            return f.canRead() ? f : null;
        }
        android.database.Cursor c = null;
        try {
            String[] cols = {android.provider.MediaStore.MediaColumns.DATA};
            c = ctx.getContentResolver().query(uri, cols, null, null, null);
            if (c == null || !c.moveToFirst()) return null;
            String path = c.getString(0);
            if (path == null || path.length() == 0) return null;
            File f = new File(path);
            if (!f.isFile() || !f.canRead() || f.length() == 0) return null;
            // Prove it opens; isFile() alone can lie across mount views.
            FileInputStream probe = new FileInputStream(f);
            probe.close();
            Log.i(TAG, "resolved direct path " + path + " (" + f.length() + " bytes)");
            return f;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Copies a content:// URI into our own cache directory.
     *
     * Only a fallback: resolvePath() handles the common case without copying.
     * This is for providers that genuinely have no backing file - Drive,
     * anything synthesising content on demand - where TDLib still needs a path.
     *
     * Copying also gives a stable file to re-read if the upload is retried.
     */
    static File stage(Context ctx, Uri uri, String displayName) throws Exception {
        String name = displayName;
        if (name == null || name.length() == 0) name = "upload";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        File dir = new File(ctx.getCacheDir(), "outgoing");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("cannot create " + dir);

        // Unique prefix so two picks of the same name do not collide.
        File out = new File(dir, System.currentTimeMillis() + "-" + name);
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("cannot open " + uri);
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                os.close();
            }
        } finally {
            in.close();
        }
        Log.i(TAG, "staged " + out.getName() + " " + out.length() + " bytes");
        return out;
    }

    /**
     * Downscales a photo to fit {@code maxPx} and re-encodes it.
     *
     * Returns the original file when it is already small enough, so a photo
     * that needs nothing is not recompressed for no reason.
     */
    static File shrinkPhoto(File src, int maxPx, int quality) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getPath(), probe);
            int w = probe.outWidth;
            int h = probe.outHeight;
            if (w <= 0 || h <= 0) return src;

            int longest = Math.max(w, h);
            // Already within budget: leave the bytes alone.
            if (longest <= maxPx && src.length() <= 400 * 1024) return src;

            int sample = 1;
            while (longest / (sample * 2) >= maxPx) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(src.getPath(), opts);
            if (bmp == null) return src;

            // inSampleSize only halves, so finish the job by scaling exactly.
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            int bl = Math.max(bw, bh);
            if (bl > maxPx) {
                float scale = (float) maxPx / bl;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                        Math.max(1, Math.round(bw * scale)),
                        Math.max(1, Math.round(bh * scale)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }

            File out = new File(src.getParentFile(), "small-" + src.getName() + ".jpg");
            OutputStream os = new FileOutputStream(out);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, os);
            } finally {
                os.close();
                bmp.recycle();
            }
            Log.i(TAG, "shrunk " + src.length() + " -> " + out.length() + " bytes");
            return out;
        } catch (Throwable t) {
            // A failed shrink must never lose the send: fall back to the original.
            Log.w(TAG, "shrink failed, sending original", t);
            return src;
        }
    }

    /** Reads image dimensions without decoding pixels, or null if unreadable. */
    static int[] dimensionsOf(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        return new int[]{o.outWidth, o.outHeight};
    }

    /**
     * Human label for how long an upload of this size will take on EVDO.
     *
     * Deliberately pessimistic: EVDO Rev. A is about 100-150 kbps up in
     * practice, and quoting the good end of that range makes every send feel
     * broken.
     */
    static String estimate(int bytes, boolean slowLink) {
        if (!slowLink) return null;
        // ~110 kbps = ~13.75 kB/s.
        int seconds = (int) Math.ceil(bytes / 13750.0);
        if (seconds < 5) return null;
        if (seconds < 60) return "~" + seconds + "s on mobile data";
        return "~" + (seconds / 60) + " min on mobile data";
    }

    /** Short human size, e.g. "248 KB". */
    static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Copies nothing; used only by callers that need a plain stream. */
    static InputStream open(File f) throws Exception {
        return new FileInputStream(f);
    }
}
