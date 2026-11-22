package ai.crystalgram.holo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Downloads and decodes media for the open conversation.
 *
 * Two layers, deliberately separate:
 *
 *  - TDLib owns the download. This asks for the smallest size that is good
 *    enough (a thumbnail for a photo, a small sticker) and only escalates to
 *    the full file when there is no preview. Downloading a 4 MB photo to show a
 *    200 px bubble is how you burn a phone's battery for nothing.
 *
 *  - This class owns the decode and the cache. TdApi.File gives a path and a
 *    state; it never gives a Bitmap, and decoding on the UI thread for a list of
 *    messages would stutter. Decoding and caching live here instead.
 *
 * Cache keyed by file id, because the path can change under a file id when a
 * download completes, but a file id always means the same bytes.
 */
final class MediaLoader {

    private static final String TAG = "CG-Media";

    /** Bumped when the photo should switch from thumbnail to full size. */
    static final int WANT_THUMB = 0;
    static final int WANT_FULL = 1;

    interface Listener {
        void onMediaUpdated(long chatId, int fileId);
    }

    private static MediaLoader instance;

    static synchronized MediaLoader get() {
        if (instance == null) instance = new MediaLoader();
        return instance;
    }

    /** file id -> decoded bitmap. ~8 MB, enough for a screenful of bubbles. */
    private final LruCache<Integer, Bitmap> bitmaps =
            new LruCache<Integer, Bitmap>(8 * 1024 * 1024) {
                @Override protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    /** file id -> what we last asked of TDLib, so we don't re-ask. */
    private final Map<Integer, Integer> wantLevel = new HashMap<Integer, Integer>();

    /** file ids whose decode failed, so we stop retrying a broken file. */
    private final Set<Integer> broken = new HashSet<Integer>();

    private final List<Listener> listeners = new ArrayList<Listener>();

    private MediaLoader() {}

    void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    Bitmap bitmap(int fileId) {
        return bitmaps.get(fileId);
    }

    // ------------------------------------------------------------ requests

    /**
     * Asks TDLib for the file if it isn't already local.
     *
     * priority 1 is used for what is on screen; TDLib orders the download queue
     * by priority, so a user scrolling back should not wait behind a queued
     * thumbnail from a message they scrolled past.
     */
    void need(int fileId, int level, int priority) {
        Integer had = wantLevel.get(fileId);
        if (had != null && had >= level && (bitmaps.get(fileId) != null
                || broken.contains(fileId))) {
            return;
        }
        wantLevel.put(fileId, level);

        Td.send(new TdApi.DownloadFile(fileId, priority, 0, 0, false),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object object) {
                        if (object instanceof TdApi.Error) {
                            Log.w(TAG, "DownloadFile failed: "
                                    + ((TdApi.Error) object).message);
                        }
                    }
                });
    }

    /** Called for every UpdateFile from TDLib. */
    void onFileUpdated(TdApi.File file) {
        if (file == null || file.local == null) return;

        // TDLib reports many intermediate states; only act on the ones that
        // change what can be drawn.
        if (file.local.isDownloadingActive) {
            notifyListeners(file.id);
            return;
        }
        if (file.local.path != null && file.local.path.length() > 0) {
            notifyListeners(file.id);
            return;
        }
        if (file.local.isDownloadingCompleted && file.local.path != null) {
            notifyListeners(file.id);
        }
    }

    private void notifyListeners(int fileId) {
        List<Listener> copy;
        synchronized (listeners) {
            copy = new ArrayList<Listener>(listeners);
        }
        for (Listener l : copy) {
            try {
                l.onMediaUpdated(0, fileId);
            } catch (Throwable t) {
                Log.w(TAG, "listener threw", t);
            }
        }
    }

    // -------------------------------------------------------------- decode

    /**
     * Decodes a file to a bitmap at a bounded size, on a background thread.
     * Returns null if the file is not local yet.
     */
    Bitmap decodeAsync(final TdApi.File file, final int maxPx) {
        if (file == null) return null;
        final int key = file.id;
        Bitmap cached = bitmaps.get(key);
        if (cached != null) return cached;
        if (broken.contains(key)) return null;

        String path = file.local == null ? null : file.local.path;
        if (path == null || path.length() == 0) return null;
        if (!new File(path).exists()) return null;

        // Decode bounds first, then a scaled decode: a full-resolution phone
        // photo is many megabytes and would OOM on a Jelly Bean heap.
        final String p = path;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(p, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        broken.add(key);
                        return;
                    }
                    int sample = 1;
                    while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2;

                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    opts.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory
                    Bitmap bmp = BitmapFactory.decodeFile(p, opts);
                    if (bmp == null) {
                        broken.add(key);
                        return;
                    }
                    bitmaps.put(key, bmp);
                    notifyListeners(key);
                } catch (Throwable t) {
                    broken.add(key);
                    Log.w(TAG, "decode failed for " + p, t);
                }
            }
        }, "media-decode").start();
        return null;
    }
}
