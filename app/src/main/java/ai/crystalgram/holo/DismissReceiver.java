package ai.crystalgram.holo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Learns that the user cleared a notification.
 *
 * A notification with no deleteIntent is invisible to the app once dismissed,
 * so TDLib would go on believing the group was still on screen and never
 * re-post it. This is the only hook Android offers for that.
 */
public class DismissReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!NotificationService.ACTION_DISMISS.equals(intent.getAction())) return;
        int groupId = intent.getIntExtra("groupId", 0);
        NotificationService.onDismissedStatic(groupId, intent.getIntExtra("notificationId", 0));
    }
}
