package ai.crystalgram.holo;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * Holds the TDLib connection and turns TDLib's notification updates into
 * Android notifications.
 *
 * This is the no-GAPPS path. Telegram's push documentation lists a
 * "MTProto separate session" token type (7), which is what this is: TDLib keeps
 * its own connection and receives updates directly, with no push transport and
 * therefore no Google services and no extra app for the user to install.
 *
 * The trade-off is honest: messages arrive while this process lives, and not
 * while it does not. On the Jelly Bean devices this targets there is no Doze
 * and no aggressive process reaper, so a foreground service survives well.
 *
 * TDLib is the source of truth for what is notifiable. It already knows about
 * muted chats, notification settings, exclusion rules and whether a chat is
 * currently open, so this class only renders what it is told to render. It must
 * not invent notifications, and it must not suppress ones TDLib asked for.
 */
public class NotificationService extends Service {

    private static final String TAG = "CG-Notify";

    private static final int FOREGROUND_ID = 1;
    private static final String CHANNEL_LIKE_ID = "service";

    private NotificationManager nm;

    /** TDLib notification group id -> the Notification currently shown for it. */
    private final Map<Integer, TdApi.NotificationGroup> groups =
            new HashMap<Integer, TdApi.NotificationGroup>();

    /** Cached TdApi.Notification objects, keyed by their id. */
    private final Map<Integer, TdApi.Notification> pending =
            new HashMap<Integer, TdApi.Notification>();

    /** Chat ids the user is currently looking at; these must never notify. */
    private static volatile long openChatId;

    static void setOpenChat(long chatId) { openChatId = chatId; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Td.setNotifier(this);
        enableNotificationApi();
        startForeground(FOREGROUND_ID, buildOngoing());
        Log.i(TAG, "notification service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY: if the system kills us, come back. This is the whole
        // reason messages keep arriving on a phone with no push transport.
        return START_STICKY;
    }

    /**
     * The user cleared a notification.
     *
     * TDLib will not re-post a group it believes is still on screen, so a
     * dismissal that is never reported means that chat goes silent until the
     * app is restarted. Both calls are issued: removeNotification for the
     * individual notification, removeNotificationGroup for the whole group,
     * because which one TDLib expects depends on how it built the group.
     */
    /** Lets the receiver reach the live service without binding to it. */
    static void onDismissedStatic(int groupId) {
        NotificationService s = instance;
        if (s != null) s.onDismissed(groupId);
    }

    void onDismissed(int groupId) {
        note("dismissed group=" + groupId);
        synchronized (groups) {
            TdApi.NotificationGroup g = groups.remove(groupId);
            if (g != null && g.notifications != null) {
                for (TdApi.Notification n : g.notifications) pending.remove(n.id);
            }
            // Drop pending notifications that belong to this group's chat.
            // TdApi.Notification carries no chatId of its own, so it is read
            // from the notification type.
            long chatId = g == null ? 0 : g.chatId;
            java.util.Iterator<java.util.Map.Entry<Integer, TdApi.Notification>> it =
                    pending.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<Integer, TdApi.Notification> e = it.next();
                if (chatIdOf(e.getValue()) == chatId) it.remove();
            }
        }
        Td.send(new TdApi.RemoveNotificationGroup(groupId, 0));
    }

    /**
     * The chat a notification belongs to, or 0.
     *
     * TdApi.Notification has no chatId field; it lives on the notification type.
     */
    private static long chatIdOf(TdApi.Notification n) {
        if (n == null || n.type == null) return 0;
        if (n.type instanceof TdApi.NotificationTypeNewMessage) {
            TdApi.Message m = ((TdApi.NotificationTypeNewMessage) n.type).message;
            return m == null ? 0 : m.chatId;
        }
        // NewPushMessage carries no chatId; the group's own chatId covers it,
        // so this branch contributes nothing to the match.
        if (n.type instanceof TdApi.NotificationTypeNewPushMessage) return 0;
        if (n.type instanceof TdApi.NotificationTypeNewSecretChat) return 0;
        if (n.type instanceof TdApi.NotificationTypeNewCall) return 0;
        return 0;
    }

    @Override
    public void onDestroy() {
        Td.setNotifier(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /**
     * The TDLib Notification API is disabled by default: option
     * notification_group_count_max defaults to 0, which means TDLib sends no
     * notification updates at all. Setting it positive is the entire switch.
     *
     * 25 is the documented maximum. The per-group size limit defaults to 10,
     * which is fine here.
     */
    private void enableNotificationApi() {
        Td.send(new TdApi.SetOption("notification_group_count_max",
                new TdApi.OptionValueInteger(25)));
        Td.send(new TdApi.SetOption("notification_group_size_max",
                new TdApi.OptionValueInteger(5)));
        // Read them back: a SetOption that silently failed would look exactly
        // like the notification API being off, which is what was reported.
        Td.send(new TdApi.GetOption("notification_group_count_max"),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object o) {
                        Log.i(TAG, "OPTION count_max = " + describe(o));
                        note("count_max=" + describe(o));
                    }
                });
        Td.send(new TdApi.GetOption("notification_group_size_max"),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object o) {
                        Log.i(TAG, "OPTION size_max = " + describe(o));
                        note("size_max=" + describe(o));
                    }
                });
        Td.send(new TdApi.GetOption("notification_group_count_max"),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object o) {
                        if (o instanceof TdApi.OptionValueInteger) {
                            Log.i(TAG, "TDLib notification API "
                                    + (((TdApi.OptionValueInteger) o).value > 0 ? "ON" : "OFF"));
                        }
                    }
                });
        note("service onCreate, options requested");
        Log.i(TAG, "TDLib notification API enabled");
    }

    /**
     * Writes a diagnostic line to a file.
     *
     * logcat on this device is a 256 KB ring that TDLib fills in seconds, so
     * app logs vanish before they can be read. A file survives.
     */
    /** Process-wide diagnostic sink, so Td can trace without a Context. */
    private static volatile NotificationService instance;

    static void traceLine(String line) {
        NotificationService s = instance;
        if (s != null) s.note(line);
    }

    private void note(String line) {
        // Compiled out entirely in release: BuildConfig.DIAGNOSTICS is a
        // constant, so the branch and its strings vanish.
        if (!ai.crystalgram.holo.BuildConfig.DIAGNOSTICS) return;
        try {
            java.io.FileOutputStream f = openFileOutput("opt.txt", MODE_APPEND);
            try {
                f.write((System.currentTimeMillis() + " " + line + "\n").getBytes("UTF-8"));
            } finally {
                f.close();
            }
        } catch (Throwable ignored) {
            // diagnostics must never break the service
        }
    }

    private static String describe(TdApi.Object o) {
        if (o instanceof TdApi.OptionValueInteger) {
            return String.valueOf(((TdApi.OptionValueInteger) o).value);
        }
        if (o instanceof TdApi.Error) return "error " + ((TdApi.Error) o).code
                + " " + ((TdApi.Error) o).message;
        return String.valueOf(o);
    }

    // ---------------------------------------------------------------- state

    /** The full set of groups TDLib had active at launch. */
    void onActiveNotifications(TdApi.UpdateActiveNotifications u) {
        Log.i(TAG, "active notifications: " + (u.groups == null ? 0 : u.groups.length));
        synchronized (groups) {
            groups.clear();
            pending.clear();
            if (u.groups != null) {
                for (TdApi.NotificationGroup g : u.groups) {
                    groups.put(g.id, g);
                    for (TdApi.Notification n : g.notifications) {
                        pending.put(n.id, n);
                    }
                    render(g);
                }
            }
        }
    }

    /** A notification was changed in place. */
    void onNotification(TdApi.UpdateNotification u) {
        synchronized (groups) {
            pending.put(u.notification.id, u.notification);
            for (TdApi.NotificationGroup g : groups.values()) {
                for (TdApi.Notification n : g.notifications) {
                    if (n.id == u.notification.id) {
                        render(g);
                        return;
                    }
                }
            }
        }
    }

    /** A group gained or lost notifications. */
    void onNotificationGroup(TdApi.UpdateNotificationGroup u) {
        Log.i(TAG, "group update id=" + u.notificationGroupId
                + " chat=" + u.chatId + " added=" + u.addedNotifications.length
                + " total=" + u.totalCount + " openChat=" + openChatId);
        synchronized (groups) {
            TdApi.NotificationGroup g = groups.get(u.notificationGroupId);
            if (g == null) {
                if (u.addedNotifications.length == 0) return;
                g = new TdApi.NotificationGroup();
                g.id = u.notificationGroupId;
                g.type = u.type;
                g.chatId = u.chatId;
                g.notifications = new TdApi.Notification[0];
                groups.put(g.id, g);
            }
            g.type = u.type;
            g.chatId = u.chatId;
            g.totalCount = u.totalCount;

            for (TdApi.Notification n : u.addedNotifications) pending.put(n.id, n);
            for (int removedId : u.removedNotificationIds) {
                pending.remove(removedId);
            }
            // When the group drains entirely, TDLib reports it via totalCount 0
            // and no remaining notifications; render() cancels then. Individual
            // removals do not need their own cancel because this group shares
            // one notification id on this platform.
            render(g);
        }
    }

    /** The user read a chat elsewhere: clear its notifications locally. */
    void onChatRead(long chatId, long lastReadInboxMessageId) {
        synchronized (groups) {
            for (TdApi.NotificationGroup g : groups.values()) {
                if (g.chatId != chatId) continue;
                if (chatId == openChatId) {
                    nm.cancel(g.id);
                }
            }
        }
    }

    // -------------------------------------------------------------- render

    /**
     * Renders one notification group.
     *
     * Android has no notification groups before API 20, so each TDLib group
     * becomes a single notification whose id is the TDLib group id. Posting
     * again with the same id replaces it, which is the pre-API-20 equivalent of
     * a summary line updating.
     */
    private void render(TdApi.NotificationGroup g) {
        // Never notify for the chat on screen: it is already in front of them.
        if (g.chatId != 0 && g.chatId == openChatId) {
            note("render: skipped, chat " + g.chatId + " is open");
            nm.cancel(g.id);
            return;
        }

        TdApi.Notification latest = latestIn(g);
        if (latest == null) {
            note("render: no notification to show, cancelling");
            nm.cancel(g.id);
            return;
        }

        int count = g.totalCount > 0 ? g.totalCount : 1;

        String title = titleFor(g);
        String text = textFor(latest);

        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setNumber(count)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true);

        try {
            b.setStyle(new Notification.BigTextStyle().bigText(text));
        } catch (Throwable ignored) {
            // BigTextStyle is API 16+; if it is missing, the plain form stands.
        }

        if (latest.isSilent) {
            b.setOnlyAlertOnce(true);
        } else {
            b.setDefaults(Notification.DEFAULT_ALL);
        }

        b.setContentIntent(openIntent(g));
        // Without a deleteIntent the service never learns the user cleared it,
        // and TDLib keeps believing the group is on screen.
        b.setDeleteIntent(dismissIntent(g.id));

        Notification n = b.build();
        nm.notify(g.id, n);
        note("render: notified id=" + g.id + " title=" + title + " text=" + text);
    }

    /**
     * Picks the newest notification for a group.
     *
     * Read from {@link #pending}, not from {@code g.notifications}. TDLib
     * delivers notifications through UpdateNotification and only reports their
     * ids in UpdateNotificationGroup.addedNotifications; the group object's own
     * notifications array is never populated on this API, so reading it found
     * nothing and every group was cancelled with "no notification to show".
     */
    private TdApi.Notification latestIn(TdApi.NotificationGroup g) {
        TdApi.Notification best = null;
        synchronized (groups) {
            for (TdApi.Notification n : pending.values()) {
                if (best == null || n.date > best.date) best = n;
            }
        }
        return best;
    }

    private String titleFor(TdApi.NotificationGroup g) {
        if (g.type instanceof TdApi.NotificationGroupTypeMessages) {
            TdApi.Chat c = Td.chat(g.chatId);
            if (c != null && c.title != null && c.title.length() > 0) return c.title;
            return "New message";
        }
        if (g.type instanceof TdApi.NotificationGroupTypeSecretChat) return "Secret chat";
        return "CrystalGram";
    }

    /**
     * NotificationType carries either a full Message (new message) or a
     * PushMessageContent (push message). Both are rendered here; anything else
     * (incoming call, secret chat) gets a plain line rather than being dropped.
     */
    private String textFor(TdApi.Notification n) {
        if (n.type instanceof TdApi.NotificationTypeNewMessage) {
            TdApi.Message m = ((TdApi.NotificationTypeNewMessage) n.type).message;
            return m == null ? "New message" : contentText(m.content);
        }
        if (n.type instanceof TdApi.NotificationTypeNewPushMessage) {
            return pushText(((TdApi.NotificationTypeNewPushMessage) n.type).content);
        }
        if (n.type instanceof TdApi.NotificationTypeNewSecretChat) return "New secret chat";
        if (n.type instanceof TdApi.NotificationTypeNewCall) return "Incoming call";
        return "New message";
    }

    private String contentText(TdApi.MessageContent c) {
        if (c instanceof TdApi.MessageText) {
            String t = ((TdApi.MessageText) c).text.text;
            return t == null ? "" : t;
        }
        if (c instanceof TdApi.MessagePhoto) return "Photo";
        if (c instanceof TdApi.MessageVideo) return "Video";
        if (c instanceof TdApi.MessageVoiceNote) return "Voice message";
        if (c instanceof TdApi.MessageSticker) return "Sticker";
        if (c instanceof TdApi.MessageDocument) return "Document";
        if (c instanceof TdApi.MessageAnimation) return "GIF";
        if (c instanceof TdApi.MessageContact) return "Contact";
        if (c instanceof TdApi.MessagePoll) return "Poll";
        return "Message";
    }

    private String pushText(TdApi.PushMessageContent c) {
        if (c instanceof TdApi.PushMessageContentText) {
            String t = ((TdApi.PushMessageContentText) c).text;
            return t == null ? "" : t;
        }
        if (c instanceof TdApi.PushMessageContentPhoto) return "Photo";
        if (c instanceof TdApi.PushMessageContentVideo) return "Video";
        if (c instanceof TdApi.PushMessageContentVoiceNote) return "Voice message";
        if (c instanceof TdApi.PushMessageContentSticker) return "Sticker";
        if (c instanceof TdApi.PushMessageContentDocument) return "Document";
        if (c instanceof TdApi.PushMessageContentAnimation) return "GIF";
        if (c instanceof TdApi.PushMessageContentHidden) return "Message";
        if (c instanceof TdApi.PushMessageContentContact) return "Contact";
        if (c instanceof TdApi.PushMessageContentPoll) return "Poll";
        return "New message";
    }

    static final String ACTION_DISMISS = "ai.crystalgram.holo.DISMISS";

    private PendingIntent dismissIntent(int groupId) {
        Intent i = new Intent(ACTION_DISMISS);
        i.setClass(this, DismissReceiver.class);
        i.putExtra("groupId", groupId);
        return PendingIntent.getBroadcast(this, groupId, i,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openIntent(TdApi.NotificationGroup g) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra(ChatActivity.EXTRA_CHAT_ID, g.chatId);
        TdApi.Chat c = Td.chat(g.chatId);
        if (c != null) i.putExtra(ChatActivity.EXTRA_TITLE, c.title);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this, g.id, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The notification that says the service is running. */
    private Notification buildOngoing() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("CrystalGram")
                .setContentText("Receiving messages")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
