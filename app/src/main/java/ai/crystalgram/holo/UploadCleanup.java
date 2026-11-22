package ai.crystalgram.holo;

import android.util.Log;

import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Deletes our own staged upload copies once TDLib has finished with them.
 *
 * Only files this app copied into cache/outgoing/ are ever touched. A file the
 * user picked in Gallery is theirs and is never deleted - that is the whole
 * point of resolving a direct path instead of always copying.
 *
 * The interesting case is the id change. A local send first carries a temporary
 * message id; UpdateMessageSendSucceeded reports the real server id alongside
 * the temporary one as oldMessageId. Tracking by the temporary id and waiting
 * for the success update is what makes "fully confirmed by TDLib" meaningful -
 * deleting at send time would race the upload.
 */
final class UploadCleanup {

    private static final String TAG = "CG-Upload";

    /** temporary message id -> staged file waiting to be deleted. */
    private static final Map<Long, File> pending = new HashMap<Long, File>();

    private UploadCleanup() {}

    /** Records a staged file against the message id TDLib will report. */
    static void track(long temporaryMessageId, File staged) {
        if (staged == null) return;
        synchronized (pending) {
            pending.put(temporaryMessageId, staged);
        }
    }

    /** TDLib confirmed the message reached the server. */
    static void onSent(TdApi.Message m) {
        if (m == null) return;
        File f = take(m.id);
        delete(f);
    }

    /**
     * The send failed for good.
     *
     * The file is kept: a failure may be transient (EVDO dropping mid-upload)
     * and the user may retry. cache/outgoing is pruned by the system and by
     * sweep() on next launch, so a permanently failed upload does not leak.
     */
    static void onFailed(TdApi.Message m) {
        if (m == null) return;
        synchronized (pending) {
            pending.remove(m.id);
        }
    }

    private static File take(long id) {
        synchronized (pending) {
            return pending.remove(id);
        }
    }

    private static void delete(File f) {
        if (f == null) return;
        try {
            if (f.exists() && f.delete()) {
                Log.i(TAG, "removed staged copy " + f.getName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not delete " + f, t);
        }
    }

    /**
     * Deletes leftovers from previous runs.
     *
     * A copy is only meaningful while its upload is in flight; once the process
     * dies nothing will ever send it again. Called once at startup.
     */
    static void sweep(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        int n = 0;
        for (File f : files) {
            if (f.isFile() && f.delete()) n++;
        }
        if (n > 0) Log.i(TAG, "swept " + n + " stale staged file(s)");
    }
}
