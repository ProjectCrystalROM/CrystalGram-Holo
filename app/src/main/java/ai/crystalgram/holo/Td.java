package ai.crystalgram.holo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

    private static volatile Client client;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context application;
    private static volatile AccountTerms accountTerms;
    private static NetworkMonitor network;
    private static TdApi.AuthorizationState authorizationState;
    private static String startupError;
    private static boolean loggingOut;
    private static final Object lock = new Object();
    private static volatile long myId;
    private static final java.util.Map<Long, TdApi.Supergroup> groups = new java.util.HashMap<>();
    private static volatile ForumActivity forum;
    private static final java.util.HashMap<Long, TdApi.Chat> chats =
            new java.util.HashMap<Long, TdApi.Chat>();
    private static final java.util.HashMap<Long, TdApi.User> users =
            new java.util.HashMap<Long, TdApi.User>();

    /** Activity references are cleared by their lifecycle callbacks. */
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

    // Startup and authorization transitions are serialized on the main looper.
    static void ensureStarted(Context context) {
        if (client != null) return;
        application = context.getApplicationContext();
        if (accountTerms == null) accountTerms = new AccountTerms((android.app.Application) application);
        startupError = null;
        authorizationState = null;
        System.loadLibrary("tdjni");
        client = Client.create(new Dispatcher(), null, new Client.ExceptionHandler() {
            @Override public void onException(Throwable error) {
                Log.e(TAG, "TDLib callback failed", error);
            }
        });
        if (network == null) {
            network = new NetworkMonitor(application);
            network.start();
        } else {
            network.sync("client restart");
        }
    }

    static AccountTerms accountTerms() { return accountTerms; }

    static boolean termsBlocked() {
        AccountTerms gate = accountTerms;
        return gate != null && gate.pending() != null;
    }

    static void onTermsGateChanged() {
        NotificationService service = notifier;
        if (service != null) service.onTermsGateChanged();
    }

    static boolean authorized() { return sessionReady() && !termsBlocked(); }

    static boolean sessionReady() {
        return !loggingOut && authorizationState instanceof TdApi.AuthorizationStateReady;
    }

    static void beginLogout() {
        loggingOut = true;
    }

    static void onLogoutError(final TdApi.Error error) {
        Log.w(TAG, "LogOut failed: " + error.code);
        main.post(new Runnable() {
            @Override public void run() {
                loggingOut = false;
                if (login != null && authorized()) {
                    login.onAuthorizationState(authorizationState);
                }
            }
        });
    }

    private static void updateAuthorization(TdApi.AuthorizationState state) {
        authorizationState = state;
        if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber
                || state instanceof TdApi.AuthorizationStateLoggingOut) accountTerms.clear();
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            TdApi.SetTdlibParameters params = new TdApi.SetTdlibParameters();
            params.databaseDirectory = application.getDir("tdlib", Context.MODE_PRIVATE)
                    .getAbsolutePath();
            params.useMessageDatabase = true;
            params.apiId = BuildConfig.TG_API_ID;
            params.apiHash = BuildConfig.TG_API_HASH;
            params.systemLanguageCode = "en";
            params.deviceModel = android.os.Build.MODEL;
            params.systemVersion = android.os.Build.VERSION.RELEASE;
            params.applicationVersion = BuildConfig.VERSION_NAME;
            send(params, new Client.ResultHandler() {
                @Override public void onResult(TdApi.Object result) {
                    if (!(result instanceof TdApi.Error)) return;
                    final String message = ((TdApi.Error) result).message;
                    main.post(new Runnable() {
                        @Override public void run() {
                            startupError = message;
                            if (login != null) login.showError(message);
                            NotificationService service = notifier;
                            if (service != null) service.stopSelf();
                        }
                    });
                }
            });
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            send(new TdApi.GetMe(), new Client.ResultHandler() {
                @Override public void onResult(TdApi.Object result) {
                    if (result instanceof TdApi.User) myId = ((TdApi.User) result).id;
                }
            });
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber
                || state instanceof TdApi.AuthorizationStateLoggingOut
                || state instanceof TdApi.AuthorizationStateClosed) {
            NotificationService service = notifier;
            if (service != null) service.stopSelf();
        }

        if (state instanceof TdApi.AuthorizationStateClosed) {
            loggingOut = false;
            client = null;
            myId = 0;
            MediaLoader.reset();
            DocumentProvider.revokeOffers();
            connectionState = null;
            synchronized (lock) {
                chats.clear();
                users.clear();
                groups.clear();
            }
            if (login != null) {
                login.onAuthorizationState(state);
                ensureStarted(application);
                return;
            }
        }
        if (login != null) login.onAuthorizationState(state);
        NotificationService service = notifier;
        if (service != null && authorized()) service.onAuthorized();
    }

    /** Our own user id, learned from the authorization state. */
    static long me() {
        return myId;
    }

    /** Cached chats, learned from UpdateNewChat. */
    static TdApi.Chat chat(long id) {
        synchronized (lock) { return chats.get(id); }
    }

    static void cacheChat(TdApi.Chat chat) {
        synchronized (lock) { chats.put(chat.id, chat); }
    }

    static TdApi.Supergroup supergroup(TdApi.Chat chat) {
        if (chat == null || !(chat.type instanceof TdApi.ChatTypeSupergroup)) return null;
        long id = ((TdApi.ChatTypeSupergroup) chat.type).supergroupId;
        synchronized (lock) { return groups.get(id); }
    }

    static ChatScope scope(TdApi.Chat chat) {
        if (chat == null) return ChatScope.EXCLUDED;
        TdApi.User peer = chat.type instanceof TdApi.ChatTypePrivate
                ? user(((TdApi.ChatTypePrivate) chat.type).userId) : null;
        return ChatScope.of(chat.type, supergroup(chat), peer);
    }

    static ChatScope scope(long chatId) { return scope(chat(chatId)); }

    interface ChatHandler {
        void onChat(TdApi.Chat chat);
    }

    /** Resolves eligibility from TDLib's offline chat and supergroup objects. */
    static void resolveChat(long chatId, final ChatHandler handler) {
        send(new TdApi.GetChat(chatId), new Client.ResultHandler() {
            @Override public void onResult(TdApi.Object result) {
                if (!(result instanceof TdApi.Chat)) {
                    handler.onChat(null);
                    return;
                }
                final TdApi.Chat chat = (TdApi.Chat) result;
                cacheChat(chat);
                if (!(chat.type instanceof TdApi.ChatTypeSupergroup)
                        || ((TdApi.ChatTypeSupergroup) chat.type).isChannel) {
                    handler.onChat(chat);
                    return;
                }
                long id = ((TdApi.ChatTypeSupergroup) chat.type).supergroupId;
                send(new TdApi.GetSupergroup(id), new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object result) {
                        if (result instanceof TdApi.Supergroup) {
                            rememberGroup((TdApi.Supergroup) result);
                            handler.onChat(chat);
                        } else {
                            handler.onChat(null);
                        }
                    }
                });
            }
        });
    }

    private static void rememberGroup(TdApi.Supergroup group) {
        java.util.List<TdApi.Chat> changed = new java.util.ArrayList<>();
        synchronized (lock) {
            TdApi.Supergroup before = groups.put(group.id, group);
            boolean rightsChanged = before != null
                    && ChatScope.canManageTopics(before.status) != ChatScope.canManageTopics(group.status);
            for (TdApi.Chat chat : chats.values()) {
                if (chat.type instanceof TdApi.ChatTypeSupergroup
                        && ((TdApi.ChatTypeSupergroup) chat.type).supergroupId == group.id
                        && (rightsChanged || ChatScope.of(chat.type, before, null) != ChatScope.of(chat.type, group, null))) {
                    changed.add(chat);
                }
            }
        }
        for (TdApi.Chat chat : changed) onChatScopeChanged(chat);
    }

    private static void onChatScopeChanged(TdApi.Chat chat) {
        MainActivity list = chatList;
        if (list != null) list.onChat(chat);
        NotificationService service = notifier;
        if (service != null) service.onChatScopeChanged(chat.id);
        ForumActivity topics = forum;
        if (topics != null) topics.onScopeChanged(chat.id);
        ChatActivity screen = conversation;
        if (screen != null) screen.onScopeChanged(chat.id);
    }

    static void bindForum(ForumActivity screen) { forum = screen; }
    static void unbindForum(ForumActivity screen) { if (forum == screen) forum = null; }

    private static void refreshForum(long chatId) {
        ForumActivity screen = forum;
        if (screen != null) screen.onTopicsChanged(chatId);
    }

    static java.util.ArrayList<TdApi.Chat> forwardDestinations() {
        java.util.ArrayList<TdApi.Chat> result = new java.util.ArrayList<>();
        synchronized (lock) {
            for (TdApi.Chat chat : chats.values()) {
                if (scope(chat) != ChatScope.EXCLUDED && !(chat.type instanceof TdApi.ChatTypeSecret)
                        && chat.positions != null && chat.positions.length != 0
                        && chat.permissions != null && chat.permissions.canSendBasicMessages) result.add(chat);
            }
        }
        java.util.Collections.sort(result, (a, b) -> String.valueOf(a.title).compareToIgnoreCase(String.valueOf(b.title)));
        return result;
    }

    static String userName(TdApi.User user) {
        if (user == null) return "";
        if (user.type instanceof TdApi.UserTypeDeleted) return "Deleted Account";
        String first = user.firstName == null ? "" : user.firstName;
        String last = user.lastName == null ? "" : user.lastName;
        return (first + " " + last).trim();
    }

    static String chatTitle(TdApi.Chat chat) {
        TdApi.User peer = chat.type instanceof TdApi.ChatTypePrivate
                ? user(((TdApi.ChatTypePrivate) chat.type).userId) : null;
        if (peer != null && peer.type instanceof TdApi.UserTypeDeleted) return "Deleted Account";
        if (chat.title != null && !chat.title.trim().isEmpty()) return chat.title;
        String name = userName(peer);
        return !name.isEmpty() ? name : "Unnamed chat";
    }

    static java.util.ArrayList<TdApi.Chat> privacyChats() {
        java.util.ArrayList<TdApi.Chat> result = new java.util.ArrayList<>();
        synchronized (lock) {
            for (TdApi.Chat chat : chats.values()) {
                if (scope(chat) != ChatScope.EXCLUDED && chat.positions != null && chat.positions.length > 0) {
                    result.add(chat);
                }
            }
        }
        return result;
    }

    static TdApi.User user(long id) {
        synchronized (lock) { return users.get(id); }
    }


    static void send(TdApi.Function<?> query) {
        send(query, null);
    }

    static void send(TdApi.Function<?> query, Client.ResultHandler handler) {
        Client c = client;
        if (c == null) {
            Log.w(TAG, "send before client started: " + query.getClass().getSimpleName());
            if (handler != null) handler.onResult(new TdApi.Error(503, "Telegram is starting"));
            return;
        }
        if (termsBlocked() && !AccountTerms.allowsWhilePending(query)) {
            if (handler != null) handler.onResult(new TdApi.Error(403, "Review the updated Telegram terms first."));
            return;
        }
        c.send(query, handler);
    }

    static void setNotifier(NotificationService s) {
        notifier = s;
    }

    static void setConnectionListener(ConnectionListener l) { connectionListener = l; }

    static TdApi.ConnectionState connectionState() { return connectionState; }

    /** Publishes foreground presence using Telegram’s account privacy settings. */
    static void setOnline(boolean online) {
        send(new TdApi.SetOption("online", new TdApi.OptionValueBoolean(online)));
    }

    static void setLogin(LoginActivity a) {
        login = a;
        if (loggingOut) {
            a.onAuthorizationState(new TdApi.AuthorizationStateLoggingOut());
        } else if (authorizationState instanceof TdApi.AuthorizationStateClosed) {
            ensureStarted(application);
        } else if (authorizationState != null) {
            a.onAuthorizationState(authorizationState);
        }
        if (startupError != null) a.showError(startupError);
    }
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
                cacheChat(nc);
                NotificationService service = notifier;
                if (service != null) service.onChatScopeChanged(nc.id);
                MainActivity a = chatList;
                if (a != null) a.onChat(((TdApi.UpdateNewChat) object).chat);

            } else if (object instanceof TdApi.UpdateSupergroup) {
                rememberGroup(((TdApi.UpdateSupergroup) object).supergroup);

            } else if (object instanceof TdApi.UpdateForumTopicInfo) {
                TdApi.ForumTopicInfo info = ((TdApi.UpdateForumTopicInfo) object).info;
                if (info != null) {
                    refreshForum(info.chatId);
                    ChatActivity screen = conversation;
                    if (screen != null) screen.onTopicInfo(info);
                }

            } else if (object instanceof TdApi.UpdateForumTopic) {
                TdApi.UpdateForumTopic update = (TdApi.UpdateForumTopic) object;
                ChatActivity screen = conversation;
                if (screen != null) {
                    screen.onReadOutbox(update.chatId, update.forumTopicId, update.lastReadOutboxMessageId);
                }
                refreshForum(update.chatId);

            } else if (object instanceof TdApi.UpdateTopicMessageCount) {
                TdApi.UpdateTopicMessageCount update = (TdApi.UpdateTopicMessageCount) object;
                if (ChatScope.forumTopic(update.topicId) > 0) refreshForum(update.chatId);

            } else if (object instanceof TdApi.UpdateChatPosition) {
                TdApi.UpdateChatPosition u = (TdApi.UpdateChatPosition) object;
                MainActivity a = chatList;
                if (a != null && (u.position.list instanceof TdApi.ChatListMain
                        || u.position.list instanceof TdApi.ChatListArchive)) {
                    a.onChatUpdated(u.chatId);
                }

            } else if (object instanceof TdApi.UpdateChatTitle) {
                MainActivity a = chatList;
                if (a != null) a.onChatUpdated(((TdApi.UpdateChatTitle) object).chatId);

            } else if (object instanceof TdApi.UpdateChatLastMessage) {
                MainActivity a = chatList;
                long id = ((TdApi.UpdateChatLastMessage) object).chatId;
                if (a != null) a.onChatUpdated(id);
                refreshForum(id);

            } else if (object instanceof TdApi.UpdateConnectionState) {
                TdApi.ConnectionState st = ((TdApi.UpdateConnectionState) object).state;
                connectionState = st;
                ConnectionListener l = connectionListener;
                if (l != null) l.onConnectionState(st);

            } else if (object instanceof TdApi.UpdateMessageEdited) {
                ChatActivity screen = conversation;
                if (screen != null) screen.onMessageEdited((TdApi.UpdateMessageEdited) object);

            } else if (object instanceof TdApi.UpdateMessageInteractionInfo) {
                ChatActivity screen = conversation;
                if (screen != null) screen.onInteractionInfo((TdApi.UpdateMessageInteractionInfo) object);

            } else if (object instanceof TdApi.UpdateUserStatus) {
                TdApi.UpdateUserStatus update = (TdApi.UpdateUserStatus) object;
                synchronized (lock) {
                    TdApi.User user = users.get(update.userId);
                    if (user != null) user.status = update.status;
                }
                ChatActivity screen = conversation;
                if (screen != null) screen.onPresence(update.userId, update.status);

            } else if (object instanceof TdApi.UpdateMessageContent) {
                TdApi.UpdateMessageContent update = (TdApi.UpdateMessageContent) object;
                ChatActivity chat = conversation;
                if (chat != null) chat.onMessageContent(update);

            } else if (object instanceof TdApi.UpdateDeleteMessages) {
                TdApi.UpdateDeleteMessages update = (TdApi.UpdateDeleteMessages) object;
                ChatActivity chat = conversation;
                if (!update.fromCache) {
                    if (chat != null) chat.onMessagesDeleted(update);
                    ForumActivity screen = forum;
                    if (screen != null) screen.onMessagesDeleted(update);
                    refreshForum(update.chatId);
                }

            } else if (object instanceof TdApi.UpdateUser) {
                TdApi.UpdateUser u = (TdApi.UpdateUser) object;
                java.util.ArrayList<TdApi.Chat> changed = new java.util.ArrayList<>();
                java.util.ArrayList<TdApi.Chat> renamed = new java.util.ArrayList<>();
                synchronized (lock) {
                    // TDLib sends UpdateUser before returning a reference to that user.
                    TdApi.User old = users.put(u.user.id, u.user);
                    boolean nameChanged = !userName(old).equals(userName(u.user));
                    for (TdApi.Chat chat : chats.values()) {
                        if (!(chat.type instanceof TdApi.ChatTypePrivate)
                                || ((TdApi.ChatTypePrivate) chat.type).userId != u.user.id) continue;
                        if (ChatScope.of(chat.type, null, old) != ChatScope.of(chat.type, null, u.user)) {
                            changed.add(chat);
                        } else if (nameChanged) {
                            renamed.add(chat);
                        }
                    }
                }
                for (TdApi.Chat chat : changed) onChatScopeChanged(chat);
                MainActivity list = chatList;
                if (list != null) for (TdApi.Chat chat : renamed) list.onChat(chat);
                ChatActivity screen = conversation;
                if (screen != null) screen.onPresence(u.user.id, u.user.status);

            } else if (object instanceof TdApi.UpdateNotificationGroup) {
                TdApi.UpdateNotificationGroup u = (TdApi.UpdateNotificationGroup) object;
                NotificationService service = notifier;
                if (service != null) service.onNotificationGroup(u);

            } else if (object instanceof TdApi.UpdateNotification) {
                TdApi.UpdateNotification u = (TdApi.UpdateNotification) object;
                NotificationService service = notifier;
                if (service != null) service.onNotification(u);

            } else if (object instanceof TdApi.UpdateActiveNotifications) {
                TdApi.UpdateActiveNotifications u = (TdApi.UpdateActiveNotifications) object;
                NotificationService service = notifier;
                if (service != null) service.onActiveNotifications(u);

            } else if (object instanceof TdApi.UpdateChatReadOutbox) {
                TdApi.UpdateChatReadOutbox update = (TdApi.UpdateChatReadOutbox) object;
                ChatActivity screen = conversation;
                if (screen != null) screen.onReadOutbox(update.chatId, 0, update.lastReadOutboxMessageId);

            } else if (object instanceof TdApi.UpdateChatReadInbox) {
                TdApi.UpdateChatReadInbox u = (TdApi.UpdateChatReadInbox) object;
                MainActivity a = chatList;
                if (a != null) a.onChatUpdated(u.chatId);

            } else if (object instanceof TdApi.UpdateChatAction) {
                TdApi.UpdateChatAction u = (TdApi.UpdateChatAction) object;
                ChatActivity c = conversation;
                if (c != null) c.onChatAction(u.chatId, u.topicId, u.senderId, u.action);

            } else if (object instanceof TdApi.UpdateNewMessage) {
                ChatActivity c = conversation;
                TdApi.Message message = ((TdApi.UpdateNewMessage) object).message;
                if (c != null) c.onNewMessage(message);
                if (ChatScope.forumTopic(message.topicId) > 0) refreshForum(message.chatId);

            } else if (object instanceof TdApi.UpdateMessageSendSucceeded) {
                TdApi.UpdateMessageSendSucceeded ok = (TdApi.UpdateMessageSendSucceeded) object;
                UploadCleanup.onSent(ok.message.chatId, ok.oldMessageId);
                ChatActivity c = conversation;
                if (c != null) {
                    TdApi.UpdateMessageSendSucceeded u = (TdApi.UpdateMessageSendSucceeded) object;
                    c.onMessageSent(u.message, u.oldMessageId);
                }

            } else if (object instanceof TdApi.UpdateMessageSendFailed) {
                TdApi.UpdateMessageSendFailed bad = (TdApi.UpdateMessageSendFailed) object;
                UploadCleanup.onFailed(bad.message.chatId, bad.oldMessageId);
                ChatActivity c = conversation;
                if (c != null) {
                    TdApi.UpdateMessageSendFailed u = (TdApi.UpdateMessageSendFailed) object;
                    c.onMessageFailed(u.message, u.oldMessageId, u.error);
                }

            } else if (object instanceof TdApi.UpdateTermsOfService) {
                TdApi.UpdateTermsOfService update = (TdApi.UpdateTermsOfService) object;
                main.post(() -> accountTerms.update(update));

            } else if (object instanceof TdApi.UpdateAuthorizationState) {
                final TdApi.AuthorizationState state =
                        ((TdApi.UpdateAuthorizationState) object).authorizationState;
                main.post(new Runnable() {
                    @Override public void run() { updateAuthorization(state); }
                });
            }
        }
    }
}
