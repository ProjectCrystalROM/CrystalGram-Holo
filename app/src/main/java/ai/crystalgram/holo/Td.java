package ai.crystalgram.holo;

import android.util.Log;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/**
 * One TDLib client for the whole process, plus a single dispatcher that routes
 * updates to whichever screen is interested.
 *
 * TDLib delivers updates on its own thread, so every handler here must be
 * thread-safe and must not touch views directly -- the activities post to the
 * main looper themselves.
 */
public final class Td {

    private static final String TAG = "CG-Td";

    private static Client client;
    private static final Object lock = new Object();
    private static volatile long myId;
    private static final java.util.HashMap<Long, TdApi.Chat> chats =
            new java.util.HashMap<Long, TdApi.Chat>();
    private static final java.util.HashMap<Long, TdApi.User> users =
            new java.util.HashMap<Long, TdApi.User>();

    /** Weak-ish references; activities register on resume and clear on pause. */
    private static volatile LoginActivity login;
    private static volatile MainActivity chatList;
    private static volatile ChatActivity conversation;
    private static volatile NotificationService notifier;
    private static volatile ConnectionListener connectionListener;
    private static volatile TdApi.ConnectionState connectionState;

    /** Screens that want to know whether we are actually connected. */
    interface ConnectionListener {
        void onConnectionState(TdApi.ConnectionState state);
    }

    private Td() {}

    static synchronized void ensureStarted() {
        if (client != null) return;
        client = Client.create(new Dispatcher(), null, null);
    }

    /** Our own user id, learned from the authorization state. */
    static long me() {
        return myId;
    }

    /** Cached chats, learned from UpdateNewChat. */
    static TdApi.Chat chat(long id) {
        synchronized (lock) { return chats.get(id); }
    }

    static TdApi.User user(long id) {
        synchronized (lock) { return users.get(id); }
    }

    /** The shared client; null until ensureStarted(). */
    static Client client() { return client; }

    static void setMe(long id) { myId = id; }

    static void send(TdApi.Function<?> query) {
        send(query, null);
    }

    static void send(TdApi.Function<?> query, Client.ResultHandler handler) {
        Client c = client;
        if (c == null) {
            Log.w(TAG, "send before client started: " + query.getClass().getSimpleName());
            return;
        }
        c.send(query, handler);
    }

    static void setNotifier(NotificationService s) { notifier = s; }

    static void setConnectionListener(ConnectionListener l) { connectionListener = l; }

    static TdApi.ConnectionState connectionState() { return connectionState; }

    /** True when TDLib can talk to the server. */
    static boolean isReady() {
        return connectionState instanceof TdApi.ConnectionStateReady;
    }

    /**
     * The "online" option tells TDLib whether the user is actively present.
     * While true, TDLib assumes the user is looking at the app and suppresses
     * message notifications entirely, so it must be false whenever no screen
     * is in front of the user.
     */
    static void setOnline(boolean online) {
        send(new TdApi.SetOption("online", new TdApi.OptionValueBoolean(online)));
    }

    static void setLogin(LoginActivity a) { login = a; }
    static void clearLogin(LoginActivity a) { if (login == a) login = null; }

    static void bindChatList(MainActivity a) { chatList = a; }
    static void unbindChatList(MainActivity a) { if (chatList == a) chatList = null; }

    static void bindConversation(ChatActivity a) { conversation = a; }
    static void unbindConversation(ChatActivity a) { if (conversation == a) conversation = null; }

    /** Single update handler for the process. */
    private static class Dispatcher implements Client.ResultHandler {
        @Override
        public void onResult(TdApi.Object object) {
            if (object instanceof TdApi.UpdateNewChat) {
                TdApi.Chat nc = ((TdApi.UpdateNewChat) object).chat;
                synchronized (lock) { chats.put(nc.id, nc); }
                MainActivity a = chatList;
                if (a != null) a.onChat(((TdApi.UpdateNewChat) object).chat);

            } else if (object instanceof TdApi.UpdateChatPosition) {
                TdApi.UpdateChatPosition u = (TdApi.UpdateChatPosition) object;
                MainActivity a = chatList;
                if (a != null && u.position.list instanceof TdApi.ChatListMain) {
                    a.onChatUpdated(u.chatId);
                }

            } else if (object instanceof TdApi.UpdateChatTitle) {
                MainActivity a = chatList;
                if (a != null) a.onChatUpdated(((TdApi.UpdateChatTitle) object).chatId);

            } else if (object instanceof TdApi.UpdateChatLastMessage) {
                MainActivity a = chatList;
                if (a != null) a.onChatUpdated(((TdApi.UpdateChatLastMessage) object).chatId);

            } else if (object instanceof TdApi.UpdateConnectionState) {
                TdApi.ConnectionState st = ((TdApi.UpdateConnectionState) object).state;
                connectionState = st;
                ConnectionListener l = connectionListener;
                if (l != null) l.onConnectionState(st);

            } else if (object instanceof TdApi.UpdateFile) {
                TdApi.UpdateFile u = (TdApi.UpdateFile) object;
                MediaLoader.get().onFileUpdated(u.file);
                ChatActivity c = conversation;
                if (c != null) c.onFileUpdated(u.file);

            } else if (object instanceof TdApi.UpdateUser) {
                TdApi.UpdateUser u = (TdApi.UpdateUser) object;
                synchronized (lock) { users.put(u.user.id, u.user); }

            } else if (object instanceof TdApi.UpdateNotificationGroup) {
                TdApi.UpdateNotificationGroup u = (TdApi.UpdateNotificationGroup) object;
                if (notifier != null) notifier.onNotificationGroup(u);

            } else if (object instanceof TdApi.UpdateNotification) {
                TdApi.UpdateNotification u = (TdApi.UpdateNotification) object;
                if (notifier != null) notifier.onNotification(u);

            } else if (object instanceof TdApi.UpdateActiveNotifications) {
                TdApi.UpdateActiveNotifications u = (TdApi.UpdateActiveNotifications) object;
                if (notifier != null) notifier.onActiveNotifications(u);

            } else if (object instanceof TdApi.UpdateChatReadInbox) {
                TdApi.UpdateChatReadInbox u = (TdApi.UpdateChatReadInbox) object;
                if (notifier != null) notifier.onChatRead(u.chatId, u.lastReadInboxMessageId);
                MainActivity a = chatList;
                if (a != null) a.onChatUpdated(u.chatId);

            } else if (object instanceof TdApi.UpdateChatAction) {
                TdApi.UpdateChatAction u = (TdApi.UpdateChatAction) object;
                ChatActivity c = conversation;
                if (c != null) c.onChatAction(u.senderId, u.action);

            } else if (object instanceof TdApi.UpdateNewMessage) {
                ChatActivity c = conversation;
                if (c != null) c.onNewMessage(((TdApi.UpdateNewMessage) object).message);

            } else if (object instanceof TdApi.UpdateMessageSendSucceeded) {
                ChatActivity c = conversation;
                if (c != null) {
                    TdApi.UpdateMessageSendSucceeded u = (TdApi.UpdateMessageSendSucceeded) object;
                    c.onMessageSent(u.message, u.oldMessageId);
                }

            } else if (object instanceof TdApi.UpdateMessageSendFailed) {
                ChatActivity c = conversation;
                if (c != null) {
                    TdApi.UpdateMessageSendFailed u = (TdApi.UpdateMessageSendFailed) object;
                    c.onMessageFailed(u.message, u.oldMessageId, u.error);
                }

            } else if (object instanceof TdApi.UpdateAuthorizationState) {
                LoginActivity l = login;
                if (l != null) {
                    l.onAuthorizationState(
                            ((TdApi.UpdateAuthorizationState) object).authorizationState);
                }
            } else if (object instanceof TdApi.UpdateChatActionBar) {
                // ignore
            } else if (object instanceof TdApi.UpdateOption) {
                // ignore; used only for the version probe
            }
        }
    }
}
