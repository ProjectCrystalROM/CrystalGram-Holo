package ai.crystalgram.holo;

import android.util.Log;

import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.HashMap;
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

    private static final Map<Long, Map<Long, File>> pending = new HashMap<>();

    private UploadCleanup() {}

    static synchronized void track(long chatId, long temporaryMessageId, File staged) {
        if (staged == null) return;
        Map<Long, File> uploads = pending.get(chatId);
        if (uploads == null) {
            uploads = new HashMap<>();
            pending.put(chatId, uploads);
        }
        uploads.put(temporaryMessageId, staged);
    }

    static void onSent(long chatId, long oldId) {
        discard(take(chatId, oldId));
    }

    static void onFailed(long chatId, long oldId) {
        take(chatId, oldId);
    }

    private static synchronized File take(long chatId, long id) {
        Map<Long, File> uploads = pending.get(chatId);
        if (uploads == null) return null;
        File file = uploads.remove(id);
        if (uploads.isEmpty()) pending.remove(chatId);
        return file;
    }

    static void discard(File f) {
        if (f == null) return;
        try {
            if (f.exists() && f.delete()) {
                Log.i(TAG, "removed staged copy " + f.getName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not delete " + f, t);
        }
    }

}
