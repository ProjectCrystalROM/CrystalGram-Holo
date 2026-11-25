package ai.crystalgram.holo;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.Build;
import android.annotation.TargetApi;
import android.app.NotificationChannel;
import android.util.Log;

import org.drinkless.tdlib.TdApi;

import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private static final String FOREGROUND_CHANNEL = "service";
    private static final String MESSAGE_CHANNEL = "messages";
    private static final String SILENT_CHANNEL = "silent_messages";
    private static final String MESSAGE_TAG = "telegram";
    private static int intentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private NotificationManager nm;

    private final Map<Integer, TdApi.NotificationGroup> groups = new HashMap<>();
    private boolean enabled;
    private boolean destroyed;

    static void start(Context context) {
        Intent intent = new Intent(context, NotificationService.class);
        if (Build.VERSION.SDK_INT >= 26) Api26.start(context, intent);
        else context.startService(intent);
    }

    /** Chat ids the user is currently looking at; these must never notify. */
    private static volatile long openChatId;

    static void setOpenChat(long chatId) { openChatId = chatId; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) Api26.createChannels(nm);
        Td.setNotifier(this);
        startForeground(FOREGROUND_ID, buildOngoing());
        Log.i(TAG, "notification service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Td.ensureStarted(this);
            if (Td.authorized()) onAuthorized();
            return START_STICKY;
        } catch (LinkageError | RuntimeException e) {
            Log.e(TAG, "TDLib service startup failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    void onAuthorized() {
        if (enabled) return;
        enabled = true;
        Td.send(new TdApi.SetOption("notification_group_size_max", new TdApi.OptionValueInteger(5)));
        Td.send(new TdApi.SetOption("notification_group_count_max", new TdApi.OptionValueInteger(25)));
    }

    static void onDismissedStatic(int groupId, int maxId) {
        NotificationService service = instance;
        if (service == null || maxId <= 0) return;
        Td.send(new TdApi.RemoveNotificationGroup(groupId, maxId));
    }

    @Override
    public void onDestroy() {
        Td.setNotifier(null);
        instance = null;
        synchronized (groups) {
            destroyed = true;
            for (int id : groups.keySet()) nm.cancel(MESSAGE_TAG, id);
            groups.clear();
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private static volatile NotificationService instance;

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

    void onActiveNotifications(TdApi.UpdateActiveNotifications update) {
        synchronized (groups) {
            if (destroyed) return;
            for (int id : groups.keySet()) nm.cancel(MESSAGE_TAG, id);
            groups.clear();
            for (TdApi.NotificationGroup group : update.groups) {
                groups.put(group.id, group);
                render(group, false);
            }
        }
    }

    void onNotification(TdApi.UpdateNotification update) {
        synchronized (groups) {
            if (destroyed) return;
            TdApi.NotificationGroup group = groups.get(update.notificationGroupId);
            if (group == null) return;
            for (int i = 0; i < group.notifications.length; i++) {
                if (group.notifications[i].id == update.notification.id) {
                    group.notifications[i] = update.notification;
                    render(group, false);
                    return;
                }
            }
        }
    }

    void onNotificationGroup(TdApi.UpdateNotificationGroup update) {
        synchronized (groups) {
            if (destroyed) return;
            TdApi.NotificationGroup group = groups.get(update.notificationGroupId);
            if (group == null) {
                group = new TdApi.NotificationGroup();
                group.id = update.notificationGroupId;
                group.notifications = new TdApi.Notification[0];
            }
            group.type = update.type;
            group.chatId = update.chatId;
            group.totalCount = update.totalCount;

            Map<Integer, TdApi.Notification> byId = new LinkedHashMap<>();
            for (TdApi.Notification n : group.notifications) byId.put(n.id, n);
            for (int id : update.removedNotificationIds) byId.remove(id);
            for (TdApi.Notification n : update.addedNotifications) byId.put(n.id, n);
            if (byId.isEmpty() || group.totalCount == 0) {
                groups.remove(group.id);
                nm.cancel(MESSAGE_TAG, group.id);
                return;
            }
            group.notifications = byId.values().toArray(new TdApi.Notification[0]);
            groups.put(group.id, group);
            render(group, update.addedNotifications.length > 0);
        }
    }

    void onChatRead(long chatId, long lastReadInboxMessageId) {
        synchronized (groups) {
            if (destroyed || chatId != openChatId) return;
            for (TdApi.NotificationGroup group : groups.values()) {
                if (group.chatId == chatId) nm.cancel(MESSAGE_TAG, group.id);
            }
        }
    }

    /**
     * Renders one notification group.
     *
     * Android has no notification groups before API 20, so each TDLib group
     * becomes a single notification whose id is the TDLib group id. Posting
     * again with the same id replaces it, which is the pre-API-20 equivalent of
     * a summary line updating.
     */
    private void render(TdApi.NotificationGroup g, boolean alert) {
        // Never notify for the chat on screen: it is already in front of them.
        if (g.chatId != 0 && g.chatId == openChatId) {
            note("render: skipped, chat " + g.chatId + " is open");
            nm.cancel(MESSAGE_TAG, g.id);
            return;
        }

        TdApi.Notification latest = latestIn(g);
        if (latest == null) {
            note("render: no notification to show, cancelling");
            nm.cancel(MESSAGE_TAG, g.id);
            return;
        }

        int count = g.totalCount > 0 ? g.totalCount : 1;

        String title = titleFor(g);
        String text = textFor(latest);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Notification.Builder b = builder(latest.isSilent ? SILENT_CHANNEL : MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setNumber(count)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true);

        b.setStyle(new Notification.BigTextStyle().bigText(text));
        b.setOnlyAlertOnce(!alert || latest.isSilent);
        if (!latest.isSilent) b.setDefaults(Notification.DEFAULT_ALL);

        b.setContentIntent(openIntent(g));
        // Without a deleteIntent the service never learns the user cleared it,
        // and TDLib keeps believing the group is on screen.
        b.setDeleteIntent(dismissIntent(g.id, latest.id));

        Notification n = b.build();
        nm.notify(MESSAGE_TAG, g.id, n);
        note("render: notified id=" + g.id);
    }

    private TdApi.Notification latestIn(TdApi.NotificationGroup group) {
        TdApi.Notification best = null;
        for (TdApi.Notification candidate : group.notifications) {
            if (best == null || candidate.id > best.id) best = candidate;
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

    private PendingIntent dismissIntent(int groupId, int maxId) {
        Intent i = new Intent(ACTION_DISMISS);
        i.setClass(this, DismissReceiver.class);
        i.putExtra("groupId", groupId);
        i.putExtra("notificationId", maxId);
        return PendingIntent.getBroadcast(this, groupId, i,
                intentFlags());
    }

    private PendingIntent openIntent(TdApi.NotificationGroup g) {
        Intent i = new Intent().setClassName(this, LoginActivity.NOTIFICATION_ENTRY);
        TdApi.Chat c = Td.chat(g.chatId);
        if (c == null || !MainActivity.ChatRow.isSupported(c.type)) {
            return PendingIntent.getActivity(this, g.id, i, intentFlags());
        }
        i.putExtra(ChatActivity.EXTRA_CHAT_ID, g.chatId);
        if (c != null) i.putExtra(ChatActivity.EXTRA_TITLE, c.title);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this, g.id, i, intentFlags());
    }

    /** The notification that says the service is running. */
    private Notification buildOngoing() {
        Intent i = new Intent(this, LoginActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i, intentFlags());

        return builder(FOREGROUND_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("CrystalGram")
                .setContentText("Receiving messages")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private Notification.Builder builder(String channel) {
        if (Build.VERSION.SDK_INT >= 26) return Api26.builder(this, channel);
        return new Notification.Builder(this);
    }

    @TargetApi(26)
    private static class Api26 {
        static void start(Context context, Intent intent) {
            context.startForegroundService(intent);
        }

        static Notification.Builder builder(Context context, String channel) {
            return new Notification.Builder(context, channel);
        }

        static void createChannels(NotificationManager manager) {
            manager.createNotificationChannel(new NotificationChannel(
                    FOREGROUND_CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW));
            manager.createNotificationChannel(new NotificationChannel(
                    MESSAGE_CHANNEL, "Messages", NotificationManager.IMPORTANCE_DEFAULT));
            manager.createNotificationChannel(new NotificationChannel(
                    SILENT_CHANNEL, "Silent messages", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
