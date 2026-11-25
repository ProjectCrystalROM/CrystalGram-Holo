package ai.crystalgram.holo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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

    interface Listener {
        void onMediaUpdated(long chatId, int fileId);
    }

    private static MediaLoader instance;

    static synchronized MediaLoader get() {
        if (instance == null) instance = new MediaLoader();
        return instance;
    }

    static synchronized void reset() {
        if (instance != null) {
            instance.decoder.shutdownNow();
            instance = null;
        }
    }

    /** file id -> decoded bitmap. ~8 MB, enough for a screenful of bubbles. */
    private final LruCache<Integer, Bitmap> bitmaps =
            new LruCache<Integer, Bitmap>(8 * 1024 * 1024) {
                @Override protected int sizeOf(Integer key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    private final Set<Integer> downloading = new HashSet<>();
    private final Set<Integer> decoding = new HashSet<>();
    private final LruCache<Integer, String> broken = new LruCache<>(128);
    private final ThreadPoolExecutor decoder = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16));

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

    void need(final TdApi.File file) {
        if (file == null || file.local == null || bitmap(file.id) != null) return;
        if (file.local.isDownloadingCompleted && decode(file)) return;
        if (!file.local.canBeDownloaded) return;
        synchronized (downloading) {
            if (!downloading.add(file.id)) return;
        }
        Td.send(new TdApi.DownloadFile(file.id, 1, 0, 0, true), new Client.ResultHandler() {
            @Override public void onResult(TdApi.Object result) {
                if (result instanceof TdApi.File) decode((TdApi.File) result);
                synchronized (downloading) { downloading.remove(file.id); }
                if (result instanceof TdApi.Error) {
                    Log.w(TAG, "DownloadFile: " + ((TdApi.Error) result).message);
                }
            }
        });
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

    private boolean decode(final TdApi.File file) {
        if (file.local == null || !file.local.isDownloadingCompleted) return false;
        final String path = file.local.path;
        if (path == null || path.length() == 0) return false;
        final int id = file.id;
        if (path.equals(broken.get(id)) || bitmap(id) != null) return true;

        synchronized (decoding) {
            if (!decoding.add(id)) return true;
        }
        try {
            decoder.execute(new Runnable() {
                @Override public void run() {
                    try {
                        if (!new File(path).isFile()) return;
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inJustDecodeBounds = true;
                        BitmapFactory.decodeFile(path, options);
                        int longest = Math.max(options.outWidth, options.outHeight);
                        if (options.outWidth <= 0 || options.outHeight <= 0) {
                            broken.put(id, path);
                            return;
                        }
                        int sample = 1;
                        while (longest / sample > 640) sample *= 2;
                        options.inJustDecodeBounds = false;
                        options.inSampleSize = sample;
                        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
                        if (bitmap == null) broken.put(id, path);
                        else {
                            bitmaps.put(id, bitmap);
                            notifyListeners(id);
                        }
                    } catch (RuntimeException | OutOfMemoryError e) {
                        Log.w(TAG, "Preview decode failed", e);
                    } finally {
                        synchronized (decoding) { decoding.remove(id); }
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            synchronized (decoding) { decoding.remove(id); }
        }
        return true;
    }
}
