package ai.crystalgram.holo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conversation view for private chats and groups.
 *
 * Chrome comes from Theme.Holo; bubbles are CrystalGram's (pastel lavender for
 * sent, pastel mint for received, single-path tails).
 *
 * Messages come from TDLib: GetChatHistory on open, then UpdateNewMessage,
 * UpdateMessageSendSucceeded / UpdateMessageSendFailed for live sends.
 */
public class ChatActivity extends Activity {

    public static final String EXTRA_CHAT_ID = "chat_id";
    public static final String EXTRA_TITLE = "title";

    private static final String TAG = "CG-Chat";

    private ListView list;
    private TextView typingLabel;
    private EditText input;
    private long chatId;
    static final String EXTRA_TOPIC_ID = "forumTopicId";
    private int topicId;
    private long lastReadOutboxId;
    private long peerId;
    private TextView replyBanner;
    private long replyMessageId;
    private TdApi.UserStatus peerStatus;
    private final Runnable refreshPresence = () -> showPresence(peerStatus);
    private MediaViewer viewer;
    private final Runnable expireMessages = () -> this.adapter.expireDue();
    private TdApi.ForumTopicInfo topicInfo;
    private Button sendButton;
    private String chatTitle;
    private float density;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private MessageAdapter adapter;
    /** Oldest message id currently held; 0 means "not known yet". */
    private long oldestMessageId;
    private Runnable typingHide;
    private TextView connBanner;
    private boolean resumed;
    private boolean sendingText;
    private boolean historyLoading;
    private boolean noOlderHistory;
    private boolean initialHistory = true;
    private int historyGeneration;
    /** Wall clock of the last typing notification we sent to the server. */
    private long lastTypingSent;
    /** ChatActionTyping repeats are throttled to this interval. */
    private static final long TYPING_INTERVAL_MS = 4000;
    private boolean typingSent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        density = getResources().getDisplayMetrics().density;

        chatId = getIntent().getLongExtra(EXTRA_CHAT_ID, 0L);
        topicId = getIntent().getIntExtra(EXTRA_TOPIC_ID, 0);

        android.app.ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setTitle(R.string.app_name);
            bar.setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        connBanner = new TextView(this);
        connBanner.setTextSize(13);
        connBanner.setTextColor(0xFFFFFFFF);
        connBanner.setBackgroundColor(0xFF3A3A3A);
        connBanner.setPadding(dp(12), dp(6), dp(12), dp(6));
        connBanner.setVisibility(View.GONE);
        root.addView(connBanner);

        list = new ListView(this);
        adapter = new MessageAdapter();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(android.widget.AbsListView v, int state) { }

            @Override
            public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                if (resumed && visible > 0) markVisibleAsRead();
                // Reaching the top means "give me older messages".
                if (first == 0 && adapter.getCount() > 0 && oldestMessageId != 0) {
                    loadOlder();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        typingLabel = new TextView(this);
        typingLabel.setTextSize(12);
        typingLabel.setTextColor(Palette.PREVIEW);
        typingLabel.setPadding(dp(12), dp(2), dp(12), dp(2));
        typingLabel.setVisibility(View.GONE);
        root.addView(typingLabel);

        replyBanner = new TextView(this);
        replyBanner.setPadding(dp(10), dp(6), dp(10), dp(6));
        replyBanner.setVisibility(View.GONE);
        replyBanner.setOnClickListener(v -> clearReply());
        root.addView(replyBanner);
        LinearLayout entry = new LinearLayout(this);
        entry.setOrientation(LinearLayout.HORIZONTAL);
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(8), dp(6), dp(8), dp(6));

        input = new EditText(this);
        input.setHint("Message");
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                onDraftChanged(e.length() > 0);
            }
        });
        entry.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button send = new Button(this);
        sendButton = send;
        send.setText("Send");
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });
        entry.addView(send);
        root.addView(entry);

        // Keep the keyboard down until the user taps the field.
        input.clearFocus();
        list.requestFocus();

        setComposerEnabled(false);
        setContentView(root);

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Td.authorized() || chatId == 0) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        Td.bindConversation(this);
        final int request = historyGeneration;
        Td.resolveChat(chatId, chat -> ui.post(() -> {
            if (isFinishing() || request != historyGeneration) return;
            ChatScope scope = chat == null ? ChatScope.EXCLUDED : Td.scope(chatId);
            if (scope == ChatScope.FORUM && topicId == 0) {
                startActivity(new Intent(this, ForumActivity.class).putExtra(EXTRA_CHAT_ID, chatId));
                finish();
                return;
            }
            if (!scope.allowsHistory(topicId)) {
                unavailable();
                return;
            }
            chatTitle = Td.chatTitle(chat);
            ChatAvatar.showInActionBar(this, chat);
            peerId = chat.type instanceof TdApi.ChatTypePrivate ? ((TdApi.ChatTypePrivate) chat.type).userId : 0;
            if (topicId == 0) {
                advanceReadOutbox(chat.lastReadOutboxMessageId);
                setTitle(chatTitle);
                loadPresence();
                openHistory();
                return;
            }
            Td.send(new TdApi.GetForumTopic(chatId, topicId), result -> ui.post(() -> {
                if (isFinishing() || request != historyGeneration) return;
                if (!(result instanceof TdApi.ForumTopic) || Td.scope(chatId) != ChatScope.FORUM) {
                    unavailable();
                    return;
                }
                TdApi.ForumTopic forumTopic = (TdApi.ForumTopic) result;
                TdApi.ForumTopicInfo info = forumTopic.info;
                if (info == null || info.chatId != chatId || info.forumTopicId != topicId) {
                    unavailable();
                    return;
                }
                advanceReadOutbox(forumTopic.lastReadOutboxMessageId);
                applyTopicInfo(info);
                openHistory();
            }));
        }));
    }

    private void loadPresence() {
        if (peerId == 0) return;
        TdApi.User cached = Td.user(peerId);
        if (cached != null) showPresence(cached.status);
        Td.send(new TdApi.GetUser(peerId), result -> {
            if (result instanceof TdApi.User) {
                TdApi.User user = (TdApi.User) result;
                onPresence(user.id, user.status);
                ui.post(() -> { if (!isFinishing()) invalidateOptionsMenu(); });
            }
        });
    }

    void onPresence(long userId, TdApi.UserStatus status) {
        ui.post(() -> {
            if (userId == peerId && resumed && !isFinishing()) showPresence(status);
        });
    }

    private void showPresence(TdApi.UserStatus status) {
        peerStatus = status;
        ui.removeCallbacks(refreshPresence);
        if (getActionBar() == null || peerId == 0 || peerId == Td.me()) return;
        TdApi.User user = Td.user(peerId);
        String subtitle = null;
        long now = System.currentTimeMillis();
        if (user != null && user.type instanceof TdApi.UserTypeBot) subtitle = "Bot";
        else if (status instanceof TdApi.UserStatusOnline) {
            long expiry = 1000L * ((TdApi.UserStatusOnline) status).expires;
            if (expiry > now) {
                subtitle = "Online";
                ui.postDelayed(refreshPresence, expiry - now);
            } else subtitle = "Last seen recently";
        } else if (status instanceof TdApi.UserStatusOffline) {
            long last = 1000L * ((TdApi.UserStatusOffline) status).wasOnline;
            if (last > 0) subtitle = "Last seen " + android.text.format.DateUtils.getRelativeTimeSpanString(last);
        } else if (status instanceof TdApi.UserStatusRecently) subtitle = "Last seen recently";
        else if (status instanceof TdApi.UserStatusLastWeek) subtitle = "Last seen within a week";
        else if (status instanceof TdApi.UserStatusLastMonth) subtitle = "Last seen within a month";
        getActionBar().setSubtitle(subtitle);
    }

    private void showBotCommands() {
        TdApi.Chat chat = Td.chat(chatId);
        if (!canCompose() || chat == null) return;
        TdApi.Function<?> query;
        if (chat.type instanceof TdApi.ChatTypeBasicGroup) {
            query = new TdApi.GetBasicGroupFullInfo(((TdApi.ChatTypeBasicGroup) chat.type).basicGroupId);
        } else if (chat.type instanceof TdApi.ChatTypeSupergroup) {
            query = new TdApi.GetSupergroupFullInfo(((TdApi.ChatTypeSupergroup) chat.type).supergroupId);
        } else if (peerId != 0) {
            query = new TdApi.GetUserFullInfo(peerId);
        } else return;
        Td.send(query, result -> ui.post(() -> {
            if (!canCompose()) return;
            if (result instanceof TdApi.Error) {
                ConversationActions.toast(this, ((TdApi.Error) result).message);
                return;
            }
            java.util.ArrayList<String> commands = new java.util.ArrayList<>();
            java.util.ArrayList<String> labels = new java.util.ArrayList<>();
            if (result instanceof TdApi.UserFullInfo) {
                TdApi.BotInfo bot = ((TdApi.UserFullInfo) result).botInfo;
                if (bot != null) addCommands(commands, labels, bot.commands, "");
            } else {
                TdApi.BotCommands[] groups = result instanceof TdApi.BasicGroupFullInfo
                        ? ((TdApi.BasicGroupFullInfo) result).botCommands
                        : result instanceof TdApi.SupergroupFullInfo
                            ? ((TdApi.SupergroupFullInfo) result).botCommands : null;
                if (groups != null) {
                    for (TdApi.BotCommands group : groups) {
                        TdApi.User bot = Td.user(group.botUserId);
                        if (bot != null && bot.usernames != null && bot.usernames.activeUsernames != null
                                && bot.usernames.activeUsernames.length != 0) {
                            addCommands(commands, labels, group.commands, "@" + bot.usernames.activeUsernames[0]);
                        }
                    }
                }
            }
            if (commands.isEmpty()) {
                ConversationActions.toast(this, "No command list is available. You can type a slash command.");
                return;
            }
            new android.app.AlertDialog.Builder(this).setTitle("Bot commands")
                    .setItems(labels.toArray(new String[0]), (d, i) -> {
                        input.setText(commands.get(i));
                        input.setSelection(input.length());
                        input.requestFocus();
                    }).show();
        }));
    }

    private static void addCommands(List<String> values, List<String> labels,
                                    TdApi.BotCommand[] commands, String suffix) {
        if (commands == null) return;
        for (TdApi.BotCommand command : commands) {
            String value = "/" + command.command + suffix;
            values.add(value);
            labels.add(value + " · " + command.description);
        }
    }

    boolean canUse(TdApi.Message message) {
        return resumed && !isFinishing() && Td.authorized() && Td.scope(chatId).allowsHistory(topicId)
                && ChatScope.matches(chatId, topicId, message)
                && !adapter.deleted.contains(message.id);
    }

    boolean canCompose() { return resumed && !isFinishing() && maySend(); }

    void replyTo(TdApi.Message message) {
        if (!canCompose() || !canUse(message)) return;
        replyMessageId = message.id;
        String summary = MainActivity.ChatRow.previewOf(message);
        replyBanner.setText(summary.length() == 0 ? "Replying · Tap to cancel"
                : "Replying to " + summary + " · Tap to cancel");
        replyBanner.setMaxLines(2);
        replyBanner.setVisibility(View.VISIBLE);
        input.requestFocus();
    }

    private void clearReply() {
        replyMessageId = 0;
        replyBanner.setVisibility(View.GONE);
    }

    private TdApi.InputMessageReplyTo reply(long id) {
        return id == 0 ? null : new TdApi.InputMessageReplyToMessage(id, null, 0, "");
    }

    void onMessageEdited(TdApi.UpdateMessageEdited update) {
        if (update.chatId != chatId) return;
        ui.post(() -> {
            for (TdApi.Message message : adapter.items) {
                if (message.id == update.messageId) {
                    message.replyMarkup = update.replyMarkup;
                    message.editDate = update.editDate;
                    adapter.notifyDataSetChanged();
                    return;
                }
            }
        });
    }

    void onInteractionInfo(TdApi.UpdateMessageInteractionInfo update) {
        if (update.chatId != chatId) return;
        ui.post(() -> {
            for (TdApi.Message message : adapter.items) {
                if (message.id == update.messageId) {
                    message.interactionInfo = update.interactionInfo;
                    adapter.notifyDataSetChanged();
                    return;
                }
            }
        });
    }

    private void unavailable() {
        Toast.makeText(this, R.string.forum_unavailable, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void openHistory() {
        resumed = true;
        setComposerEnabled(maySend());
        Td.setOnline(true);
        Td.send(new TdApi.OpenChat(chatId));
        NotificationService.setOpenChat(chatId, topicId);
        MediaLoader.get().addListener(mediaListener);
        startNetworkWatch();
        adapter.expireDue();
        loadHistory(0);
    }

    private void setComposerEnabled(boolean enabled) {
        input.setEnabled(enabled);
        sendButton.setEnabled(enabled);
        invalidateOptionsMenu();
    }

    private void applyTopicInfo(TdApi.ForumTopicInfo info) {
        topicInfo = info;
        if (!maySend()) clearTyping();
        setTitle(info.name);
        if (getActionBar() != null) getActionBar().setSubtitle(chatTitle);
        if (!maySend()) input.setHint(R.string.forum_topic_closed);
        else input.setHint("Message");
        setComposerEnabled(resumed && maySend());
    }

    void onChatPhotoChanged(long id) {
        if (id == chatId) ui.post(() -> {
            if (resumed && !isFinishing()) ChatAvatar.showInActionBar(this, Td.chat(id));
        });
    }

    void onTopicInfo(TdApi.ForumTopicInfo info) {
        if (info.chatId != chatId || info.forumTopicId != topicId) return;
        ui.post(() -> {
            if (!isFinishing() && Td.scope(chatId) == ChatScope.FORUM) applyTopicInfo(info);
        });
    }

    void onScopeChanged(long id) {
        if (id == chatId) ui.post(() -> {
            if (!resumed) return;
            if (!Td.scope(chatId).allowsHistory(topicId)) unavailable();
            else if (topicInfo != null) applyTopicInfo(topicInfo);
        });
    }

    void onReadOutbox(long id, int updateTopicId, long lastReadId) {
        if (id == chatId && updateTopicId == topicId) {
            ui.post(() -> advanceReadOutbox(lastReadId));
        }
    }

    private void advanceReadOutbox(long lastReadId) {
        if (lastReadId <= lastReadOutboxId) return;
        lastReadOutboxId = lastReadId;
        adapter.notifyDataSetChanged();
    }

    private TdApi.MessageTopic topic() {
        return topicId == 0 ? null : new TdApi.MessageTopicForum(topicId);
    }

    private boolean maySend() {
        TdApi.Chat chat = Td.chat(chatId);
        return Td.authorized() && Td.scope(chat).allowsHistory(topicId)
                && (topicId == 0 || ChatScope.canSendToTopic(topicInfo, Td.supergroup(chat)));
    }

    @Override
    protected void onPause() {
        historyGeneration++;
        if (viewer != null) viewer.close();
        ui.removeCallbacks(expireMessages);
        ui.removeCallbacks(refreshPresence);
        if (resumed) {
            resumed = false;
            setComposerEnabled(false);
            historyLoading = false;
            Td.setConnectionListener(null);
            MediaLoader.get().removeListener(mediaListener);
            NotificationService.setOpenChat(0, 0);
            clearTyping();
            hideTyping();
            Td.send(new TdApi.CloseChat(chatId));
            Td.setOnline(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Td.unbindConversation(this);
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    private void loadOlder() {
        if (oldestMessageId != 0 && !noOlderHistory) loadHistory(oldestMessageId);
    }

    private void loadHistory(final long from) {
        if (!resumed || historyLoading || !Td.scope(chatId).allowsHistory(topicId)) return;
        historyLoading = true;
        final int generation = historyGeneration;
        TdApi.Function<TdApi.Messages> query = topicId == 0
                ? new TdApi.GetChatHistory(chatId, from, 0, 50, false)
                : new TdApi.GetForumTopicHistory(chatId, topicId, from, 0, 50);
        Td.send(query,
                new Client.ResultHandler() {
                    @Override public void onResult(final TdApi.Object result) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (generation != historyGeneration || !resumed
                                        || !Td.scope(chatId).allowsHistory(topicId)) return;
                                if (result instanceof TdApi.Messages) {
                                    TdApi.Message[] messages = ((TdApi.Messages) result).messages;
                                    View anchor = list.getChildAt(0);
                                    int first = list.getFirstVisiblePosition();
                                    int top = anchor == null ? 0 : anchor.getTop();
                                    int before = adapter.getCount();
                                    boolean atBottom = before == 0 || list.getLastVisiblePosition() == before - 1;
                                    int added = adapter.mergeHistory(messages);
                                    if (from != 0) {
                                        noOlderHistory = added == 0;
                                        if (added != 0) list.setSelectionFromTop(first + added, top);
                                    } else if (initialHistory || atBottom) {
                                        initialHistory = false;
                                        scrollToBottom();
                                    }
                                } else if (result instanceof TdApi.Error) {
                                    Log.w(TAG, "GetChatHistory: " + ((TdApi.Error) result).message);
                                }
                                historyLoading = false;
                            }
                        });
                    }
                });
    }

    private static final int REQ_PICK = 1001;
    private static final int[] MEDIA_TIMERS = {0, 5, 10, 30, 60};

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        if (resumed && maySend()) {
            menu.add(0, 3, 0, "Attach");
            TdApi.Chat chat = Td.chat(chatId);
            if (chat != null && (chat.type instanceof TdApi.ChatTypeBasicGroup
                    || chat.type instanceof TdApi.ChatTypeSupergroup)) {
                menu.add(0, 4, 1, "Bot commands");
            } else if (peerId != 0 && peerId != Td.me()) {
                menu.add(0, 6, 1, "Attach disappearing media");
            }
        }
        if (resumed) menu.add(0, 5, 2, "Chat settings");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 3 && canCompose()) { pickFile(REQ_PICK, "*/*"); return true; }
        if (item.getItemId() == 6 && canCompose() && peerId != 0 && peerId != Td.me()) {
            chooseDisappearingMedia();
            return true;
        }
        if (item.getItemId() == 4 && canCompose()) { showBotCommands(); return true; }
        if (item.getItemId() == 5 && resumed) {
            ChatSettings.show(this, chatId, new TdApi.ChatListMain(), () -> {});
            return true;
        }
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void sendMessage() {
        if (!resumed || !maySend()) return;
        String text = input.getText().toString();
        if (sendingText || text.trim().length() == 0) return;
        sendingText = true;
        TdApi.TextEntity[] entities = null;
        if (text.startsWith("/")) {
            int end = 0;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
            if (text.substring(0, end).matches("/[A-Za-z0-9_]{1,32}(@[A-Za-z0-9_]{1,32})?")) {
                entities = new TdApi.TextEntity[] {new TdApi.TextEntity(0, end, new TdApi.TextEntityTypeBotCommand())};
            }
        }
        TdApi.InputMessageText content = ConversationActions.text(text, entities);
        final long sentReply = replyMessageId;
        final String draft = text;
        Td.send(new TdApi.SendMessage(chatId, topic(), reply(sentReply),
                new TdApi.MessageSendOptions(), null, content), new Client.ResultHandler() {
            @Override public void onResult(final TdApi.Object result) {
                ui.post(new Runnable() {
                    @Override public void run() {
                        sendingText = false;
                        if (isFinishing()) return;
                        if (result instanceof TdApi.Error) {
                            Toast.makeText(ChatActivity.this, ((TdApi.Error) result).message,
                                    Toast.LENGTH_LONG).show();
                        } else if (result instanceof TdApi.Message
                                && draft.equals(input.getText().toString())) {
                            input.setText("");
                            if (replyMessageId == sentReply) clearReply();
                        }
                    }
                });
            }
        });
    }

    // -------------------------------------------------------- TDLib -> UI hooks

    /** UpdateChatAction: someone else started or stopped typing. */
    void onChatAction(long id, TdApi.MessageTopic sourceTopic,
                      final TdApi.MessageSender sender, final TdApi.ChatAction action) {
        if (id != chatId || ChatScope.forumTopic(sourceTopic) != topicId) return;
        if (sender instanceof TdApi.MessageSenderUser
                && ((TdApi.MessageSenderUser) sender).userId == Td.me()) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (!resumed) return;
                String who = senderName(sender);
                if (action instanceof TdApi.ChatActionTyping) {
                    showTyping(who == null ? "typing\u2026" : who + " is typing\u2026");
                } else if (action instanceof TdApi.ChatActionCancel) {
                    hideTyping();
                } else {
                    hideTyping();
                }
            }
        });
    }

    private String senderName(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser) {
            long uid = ((TdApi.MessageSenderUser) sender).userId;
            if (uid == Td.me()) return null;
            TdApi.User u = Td.user(uid);
            if (u == null) return null;
            String n = (u.firstName + " " + u.lastName).trim();
            return n.length() == 0 ? null : n;
        }
        return null;
    }

    private void showTyping(String text) {
        typingLabel.setText(text);
        typingLabel.setVisibility(View.VISIBLE);
        if (typingHide != null) typingLabel.removeCallbacks(typingHide);
        typingHide = new Runnable() {
            @Override public void run() { hideTyping(); }
        };
        typingLabel.postDelayed(typingHide, 6000);
    }

    private void hideTyping() {
        if (typingHide != null) typingLabel.removeCallbacks(typingHide);
        typingHide = null;
        typingLabel.setVisibility(View.GONE);
    }

    void onNewMessage(final TdApi.Message message) {
        if (!ChatScope.matches(chatId, topicId, message)) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (!Td.scope(chatId).allowsHistory(topicId)) return;
                boolean atBottom = adapter.getCount() == 0
                        || list.getLastVisiblePosition() == adapter.getCount() - 1;
                adapter.upsert(message);
                if (resumed && (atBottom || message.isOutgoing)) scrollToBottom();
            }
        });
    }

    void onMessageSent(final TdApi.Message message, final long oldMessageId) {
        if (!ChatScope.matches(chatId, topicId, message)) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (Td.scope(chatId).allowsHistory(topicId)) adapter.replaceById(oldMessageId, message);
            }
        });
    }

    void onMessageFailed(final TdApi.Message message, final long oldMessageId,
                         final TdApi.Error error) {
        if (!ChatScope.matches(chatId, topicId, message)) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (!Td.scope(chatId).allowsHistory(topicId)) return;
                adapter.replaceById(oldMessageId, message);
                adapter.markFailed(message.id, error == null ? "not sent" : error.message);
            }
        });
    }

    void onMessageContent(final TdApi.UpdateMessageContent update) {
        if (update.chatId != chatId) return;
        ui.post(new Runnable() {
            @Override public void run() {
                for (TdApi.Message message : adapter.items) {
                    if (message.id == update.messageId) {
                        if (viewer != null && MediaViewer.isExpired(update.newContent)) {
                            viewer.expired(update.messageId);
                        }
                        message.content = update.newContent;
                        adapter.notifyDataSetChanged();
                        break;
                    }
                }
            }
        });
    }

    void onMessagesDeleted(final TdApi.UpdateDeleteMessages update) {
        if (update.chatId != chatId) return;
        ui.post(new Runnable() {
            @Override public void run() {
                if (update.isPermanent) {
                    for (long id : update.messageIds) {
                        if (ChatScope.isDeletedTopicRoot(topicInfo, id)) {
                            topicInfo = null;
                            setComposerEnabled(false);
                            unavailable();
                            return;
                        }
                        adapter.deleted.add(id);
                    }
                }
                if (viewer != null) viewer.deleted(update.messageIds);
                Set<Long> removed = new HashSet<>();
                for (long id : update.messageIds) {
                    removed.add(id);
                    adapter.expiresAt.remove(id);
                    if (replyMessageId == id) clearReply();
                }
                java.util.Iterator<TdApi.Message> messages = adapter.items.iterator();
                while (messages.hasNext()) {
                    if (removed.contains(messages.next().id)) messages.remove();
                }
                adapter.publish();
            }
        });
    }

    /**
     * Telegram API ToS 1.4 forbids interfering with typing status, in either
     * direction: we must report our own typing, and we must show others'.
     *
     * TDLib throttles ChatActionTyping server-side (a notification is valid for
     * ~5s), so this repeats at most every 4s while the draft is non-empty, and
     * clears it as soon as the draft empties.
     */
    private void onDraftChanged(boolean hasText) {
        if (!resumed || !maySend()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (hasText) {
            if (!typingSent || now - lastTypingSent >= TYPING_INTERVAL_MS) {
                sendChatAction(new TdApi.ChatActionTyping());
                typingSent = true;
                lastTypingSent = now;
            }
        } else if (typingSent) {
            // Passing null cancels the currently active action.
            sendChatAction(null);
            typingSent = false;
        }
    }

    private void sendChatAction(TdApi.ChatAction action) {
        if (Td.scope(chatId).allowsHistory(topicId)) {
            Td.send(new TdApi.SendChatAction(chatId, topic(), null, action));
        }
    }

    private void clearTyping() {
        if (typingSent) {
            sendChatAction(null);
            typingSent = false;
        }
    }

    // ---- media -----------------------------------------------------------

    private final MediaLoader.Listener mediaListener = new MediaLoader.Listener() {
        @Override public void onMediaUpdated(long ignoredChatId, int fileId) {
            ui.post(new Runnable() {
                @Override public void run() {
                    if (!resumed) return;
                    TdApi.Chat chat = Td.chat(chatId);
                    if (ChatAvatar.matches(chat, fileId)) ChatAvatar.showInActionBar(ChatActivity.this, chat);
                    else adapter.notifyDataSetChanged();
                }
            });
        }
    };

    private void scrollToBottom() {
        if (adapter.getCount() > 0) list.setSelection(adapter.getCount() - 1);
    }

    private void sendViewMessages(List<Long> ids) {
        if (ids.isEmpty()) return;
        Log.i(TAG, "ViewMessages: " + ids.size() + " ids");
        long[] arr = new long[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        TdApi.MessageSource source = topicId == 0 ? null : new TdApi.MessageSourceForumTopicHistory();
        Td.send(new TdApi.ViewMessages(chatId, arr, source, false), result -> {
            if (result instanceof TdApi.Error) {
                long newest = arr[arr.length - 1];
                ui.post(() -> {
                    if (lastViewedIncomingId == newest) lastViewedIncomingId = 0;
                });
            }
        });
    }

    private long lastViewedIncomingId;

    private void markVisibleAsRead() {
        if (!Td.scope(chatId).allowsHistory(topicId)) return;
        if (adapter.getCount() == 0) return;
        if (list.getLastVisiblePosition() < list.getFirstVisiblePosition()) return;
        int first = Math.max(0, list.getFirstVisiblePosition());
        int last = Math.min(adapter.getCount() - 1, list.getLastVisiblePosition());
        if (last < first) return;

        List<Long> ids = new ArrayList<Long>();
        for (int i = first; i <= last; i++) {
            TdApi.Message m = (TdApi.Message) adapter.getItem(i);
            if (m != null && !m.isOutgoing) ids.add(m.id);
        }
        if (ids.isEmpty()) return;
        long newest = ids.get(ids.size() - 1);
        if (newest <= lastViewedIncomingId) return;
        lastViewedIncomingId = newest;
        sendViewMessages(ids);
    }

    // ------------------------------------------------------------------ adapter

    private class MessageAdapter extends BaseAdapter {
        private final Set<Long> deleted = new HashSet<>();
        private final java.util.Map<Long, Long> expiresAt = new java.util.HashMap<>();
        private final List<TdApi.Message> items = new ArrayList<TdApi.Message>();
        private final Set<Long> failed = new HashSet<Long>();

        int mergeHistory(TdApi.Message[] page) {
            if (page == null) return 0;
            Set<Long> known = new HashSet<>();
            for (TdApi.Message message : items) known.add(message.id);
            int before = items.size();
            for (TdApi.Message message : page) {
                if (ChatScope.matches(chatId, topicId, message)
                        && !deleted.contains(message.id) && known.add(message.id)) {
                    items.add(message);
                    rememberExpiry(message);
                }
            }
            if (items.size() != before) publish();
            scheduleExpiry();
            return items.size() - before;
        }

        private void publish() {
            java.util.Collections.sort(items, new java.util.Comparator<TdApi.Message>() {
                @Override public int compare(TdApi.Message a, TdApi.Message b) {
                    if (a.id == b.id) return 0;
                    return a.id < b.id ? -1 : 1;
                }
            });
            oldestMessageId = items.isEmpty() ? 0 : items.get(0).id;
            notifyDataSetChanged();
        }

        void upsert(TdApi.Message message) {
            replaceById(message.id, message);
        }

        void replaceById(long oldId, TdApi.Message message) {
            for (int i = items.size() - 1; i >= 0; i--) {
                long id = items.get(i).id;
                if (id == oldId || id == message.id) items.remove(i);
            }
            failed.remove(oldId);
            if (oldId != message.id) expiresAt.remove(oldId);
            if (deleted.contains(message.id)) {
                publish();
                return;
            }
            items.add(message);
            rememberExpiry(message);
            scheduleExpiry();
            publish();
        }

        private void rememberExpiry(TdApi.Message message) {
            if (message.autoDeleteIn <= 0) return;
            long deadline = android.os.SystemClock.elapsedRealtime()
                    + Math.max(1, (long) (message.autoDeleteIn * 1000));
            Long old = expiresAt.get(message.id);
            if (old == null || deadline < old) expiresAt.put(message.id, deadline);
        }

        private void scheduleExpiry() {
            ui.removeCallbacks(expireMessages);
            if (!resumed || expiresAt.isEmpty()) return;
            long next = Long.MAX_VALUE;
            for (long deadline : expiresAt.values()) next = Math.min(next, deadline);
            ui.postDelayed(expireMessages, Math.max(0, next - android.os.SystemClock.elapsedRealtime()));
        }

        private void expireDue() {
            long now = android.os.SystemClock.elapsedRealtime();
            boolean changed = false;
            java.util.Iterator<TdApi.Message> iterator = items.iterator();
            while (iterator.hasNext()) {
                TdApi.Message message = iterator.next();
                Long deadline = expiresAt.get(message.id);
                if (deadline != null && deadline <= now) {
                    iterator.remove();
                    expiresAt.remove(message.id);
                    deleted.add(message.id);
                    if (viewer != null) viewer.deleted(new long[] {message.id});
                    if (replyMessageId == message.id) clearReply();
                    changed = true;
                }
            }
            if (changed) publish();
            scheduleExpiry();
        }

        void markFailed(long id, String reason) {
            failed.add(id);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        private String serviceText(TdApi.Message message) {
            TdApi.Chat chat = Td.chat(chatId);
            return ServiceMessage.describe(message.content, chat != null && chat.type instanceof TdApi.ChatTypeSecret);
        }

        @Override public int getViewTypeCount() { return 2; }

        @Override public int getItemViewType(int position) {
            return serviceText(items.get(position)) == null ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TdApi.Message m = items.get(position);
            String event = serviceText(m);
            if (event != null) {
                TextView view = convertView instanceof TextView ? (TextView) convertView : new TextView(ChatActivity.this);
                view.setText(event);
                view.setGravity(Gravity.CENTER);
                view.setTextSize(13);
                view.setTextColor(Palette.PREVIEW);
                view.setPadding(dp(18), dp(8), dp(18), dp(8));
                view.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
                return view;
            }
            Row row = (convertView instanceof Row) ? (Row) convertView : new Row(ChatActivity.this);
            boolean outgoing = m.isOutgoing;
            String text = textOf(m);
            String time = timeOf(m);
            boolean disappearing = MediaViewer.isProtected(m);
            MediaVm media = disappearing ? null : MediaVm.of(m, MediaLoader.get());
            if (disappearing) {
                text = m.isOutgoing ? "Disappearing media sent" : "Disappearing media · Tap to view";
            }
            String reactions = ConversationActions.reactionsText(m);
            if (reactions.length() != 0) text += "\n" + reactions;
            if (m.replyTo instanceof TdApi.MessageReplyToMessage) {
                TdApi.MessageReplyToMessage reply = (TdApi.MessageReplyToMessage) m.replyTo;
                String excerpt = reply.content instanceof TdApi.MessageText
                        ? ((TdApi.MessageText) reply.content).text.text : "Message";
                if (excerpt.length() > 120) excerpt = excerpt.substring(0, 120) + "…";
                text = "↪ " + excerpt + "\n" + text;
            }
            OutgoingReceipt receipt = OutgoingReceipt.of(m, failed.contains(m.id), lastReadOutboxId);
            row.bind(withTimestamp(text, time, outgoing, receipt), outgoing, media, iconFor(m));
            row.bubble.setOnLongClickListener(v -> {
                ConversationActions.menu(ChatActivity.this, m);
                return true;
            });
            row.bubble.setOnClickListener(null);
            row.bubble.setClickable(false);
            row.mediaBox.setOnClickListener(null);
            row.mediaBox.setClickable(false);
            if (m.content instanceof TdApi.MessageDocument) {
                row.bubble.setOnClickListener(v -> DocumentViewer.open(ChatActivity.this, m));
                row.mediaBox.setOnClickListener(v -> DocumentViewer.open(ChatActivity.this, m));
            } else if (MediaViewer.canOpen(m)) {
                View.OnClickListener open = v -> {
                    if (!canUse(m)) return;
                    if (viewer == null) viewer = new MediaViewer(ChatActivity.this, ui);
                    viewer.open(m);
                };
                row.bubble.setOnClickListener(open);
                row.mediaBox.setOnClickListener(open);
            }
            MessageWidgets.bind(ChatActivity.this, row.widgets, m);
            row.widgets.setVisibility(row.widgets.getChildCount() == 0 ? View.GONE : View.VISIBLE);
            return row;
        }

        private int iconFor(TdApi.Message m) {
            if (m.content instanceof TdApi.MessageDocument) {
                return android.R.drawable.ic_menu_save;
            }
            if (m.content instanceof TdApi.MessageVideo) {
                return android.R.drawable.ic_media_play;
            }
            return 0;
        }

        private String textOf(TdApi.Message m) {
            if (MediaViewer.isExpired(m.content)) return "Media expired";
            if (m.content instanceof TdApi.MessagePoll) {
                return MessageWidgets.pollText(((TdApi.MessagePoll) m.content).poll);
            }
            if (m.content instanceof TdApi.MessageText) {
                return ((TdApi.MessageText) m.content).text.text;
            }
            // Captions belong under the media; the media itself is drawn by
            // MediaVm. No bracketed placeholders.
            if (m.content instanceof TdApi.MessagePhoto) {
                return captionOf(((TdApi.MessagePhoto) m.content).caption);
            }
            if (m.content instanceof TdApi.MessageVideo) {
                return captionOf(((TdApi.MessageVideo) m.content).caption);
            }
            if (m.content instanceof TdApi.MessageDocument) {
                return captionOf(((TdApi.MessageDocument) m.content).caption);
            }
            if (m.content instanceof TdApi.MessageSticker) return "";
            if (m.content instanceof TdApi.MessageVoiceNote) return "Voice message · Tap to play";
            if (m.content instanceof TdApi.MessageVideoNote) return "Video message · Tap to play";
            if (m.content instanceof TdApi.MessageAudio) {
                TdApi.Audio audio = ((TdApi.MessageAudio) m.content).audio;
                return audio == null || audio.title == null || audio.title.isEmpty() ? "Audio · Tap to play" : audio.title;
            }
            return "";
        }

        private String captionOf(TdApi.FormattedText ft) {
            return ft == null || ft.text == null ? "" : ft.text;
        }

        private String timeOf(TdApi.Message m) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(m.date * 1000L);
            return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE));
        }
    }

    /**
     * Message text plus a timestamp on its own line: smaller, muted, aligned to
     * the tail side, with the outgoing receipt beside it.
     */
    private CharSequence withTimestamp(String text, String time, boolean outgoing,
                                       OutgoingReceipt receipt) {
        int status;
        switch (receipt) {
            case SENDING: status = R.string.receipt_sending; break;
            case SENT: status = R.string.receipt_sent; break;
            case SEEN: status = R.string.receipt_seen; break;
            case FAILED: status = R.string.receipt_failed; break;
            case SCHEDULED: status = R.string.receipt_scheduled; break;
            default: status = 0;
        }
        String tail = status == 0 ? time : getString(R.string.receipt_timestamp, time, getString(status));
        boolean failed = receipt == OutgoingReceipt.FAILED;
        SpannableString s = new SpannableString(text + "\n" + tail);
        int start = text.length() + 1;
        s.setSpan(new RelativeSizeSpan(0.75f), start, s.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(failed ? Palette.FAILED : Palette.BUBBLE_TIME),
                start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.AlignmentSpan.Standard(
                        outgoing ? android.text.Layout.Alignment.ALIGN_OPPOSITE
                                 : android.text.Layout.Alignment.ALIGN_NORMAL),
                start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** Full-width row holding one bubble, aligned by direction. */
    private class Row extends LinearLayout {
        private final TextView bubble;
        private final ImageView image;
        private final LinearLayout mediaBox;
        private final TextView mediaLabel;
        private final ProgressBar progress;
        private final LinearLayout widgets;

        Row(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            int margin = dp(6);
            setPadding(dp(8), margin, dp(8), margin);

            LinearLayout column = new LinearLayout(ctx);
            column.setOrientation(VERTICAL);

            // Media sits above the text inside the same bubble, as in Telegram.
            mediaBox = new LinearLayout(ctx);
            mediaBox.setOrientation(VERTICAL);
            mediaBox.setVisibility(GONE);

            image = new ImageView(ctx);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setVisibility(GONE);
            mediaBox.addView(image, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            mediaLabel = new TextView(ctx);
            mediaLabel.setTextSize(14);
            mediaLabel.setTextColor(Palette.BUBBLE_TEXT);
            mediaLabel.setVisibility(GONE);
            mediaBox.addView(mediaLabel);

            progress = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setVisibility(GONE);
            mediaBox.addView(progress, new LinearLayout.LayoutParams(dp(140), dp(4)));

            column.addView(mediaBox);

            bubble = new TextView(ctx);
            bubble.setTextSize(15);
            bubble.setTextColor(Palette.BUBBLE_TEXT);
            bubble.setPadding(dp(11), dp(7), dp(11), dp(7));
            column.addView(bubble);
            widgets = new LinearLayout(ctx);
            widgets.setOrientation(VERTICAL);
            widgets.setVisibility(GONE);
            column.addView(widgets);

            addView(column, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        /**
         * @param iconRes drawable for a non-image attachment (file, sticker,
         *                video), or 0 for none
         */
        void bind(CharSequence content, boolean outgoing) {
            bind(content, outgoing, null, 0);
        }

        void bind(CharSequence content, boolean outgoing, MediaVm media, int iconRes) {
            bubble.setText(content);
            LayoutParams lp = (LayoutParams) getChildAt(0).getLayoutParams();
            lp.gravity = outgoing ? Gravity.END : Gravity.START;
            getChildAt(0).setBackgroundDrawable(outgoing
                    ? BubbleDrawable.sent(density)
                    : BubbleDrawable.received(density));
            setGravity(outgoing ? Gravity.END : Gravity.START);

            mediaBox.setVisibility(media == null ? GONE : VISIBLE);
            if (media == null) {
                image.setVisibility(GONE);
                mediaLabel.setVisibility(GONE);
                progress.setVisibility(GONE);
                return;
            }

            // Bitmap already decoded and known by id.
            android.graphics.Bitmap bmp = media.bitmap();
            if (bmp != null) {
                image.setImageBitmap(bmp);
                image.setVisibility(VISIBLE);
                mediaLabel.setVisibility(GONE);
                image.getLayoutParams().width = Math.min(bmp.getWidth(), dp(220));
                image.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            } else {
                image.setVisibility(GONE);
                mediaLabel.setVisibility(VISIBLE);
                mediaLabel.setText(media.label);
                if (iconRes != 0) {
                    mediaLabel.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0);
                    mediaLabel.setCompoundDrawablePadding(dp(6));
                }
            }

            if (media.state == MediaVm.PENDING || media.state == MediaVm.DOWNLOADING) {
                progress.setVisibility(VISIBLE);
                progress.setIndeterminate(true);
            } else {
                progress.setVisibility(GONE);
            }
        }
    }

    // ---- connection -------------------------------------------------------

    /**
     * Same connection indicator the chat list shows, for the same reason: a
     * message that fails to send because the connection dropped should not look
     * like a bug in the chat.
     *
     * Upstream Telegram shows this too; the states come straight from TDLib.
     */
    private void startNetworkWatch() {
        Td.setConnectionListener(new Td.ConnectionListener() {
            @Override public void onConnectionState(final TdApi.ConnectionState st) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!resumed) return;
                        showConnState(st);
                        if (st instanceof TdApi.ConnectionStateReady && adapter.getCount() == 0) loadHistory(0);
                    }
                });
            }
        });
        if (Td.connectionState() != null) showConnState(Td.connectionState());
    }

    private void showConnState(TdApi.ConnectionState st) {
        if (st instanceof TdApi.ConnectionStateReady) {
            connBanner.setVisibility(View.GONE);
            return;
        }
        String text;
        if (st instanceof TdApi.ConnectionStateWaitingForNetwork) {
            text = "Waiting for network\u2026";
        } else if (st instanceof TdApi.ConnectionStateConnecting) {
            text = "Connecting\u2026";
        } else if (st instanceof TdApi.ConnectionStateConnectingToProxy) {
            text = "Connecting to proxy\u2026";
        } else if (st instanceof TdApi.ConnectionStateUpdating) {
            text = "Updating\u2026";
        } else {
            text = "Connecting\u2026";
        }
        connBanner.setText(text);
        connBanner.setVisibility(View.VISIBLE);
    }



    // ---- sending media ----------------------------------------------------

    /**
     * Opens the system picker.
     *
     * ACTION_GET_CONTENT with * / * is the one intent that works across the
     * file managers and gallery apps actually present on Jelly Bean without
     * bundling a picker of our own.
     */
    private void chooseDisappearingMedia() {
        String[] choices = {"View once", "5 seconds", "10 seconds", "30 seconds", "60 seconds"};
        new android.app.AlertDialog.Builder(this).setTitle("Disappearing photo or video")
                .setItems(choices, (d, i) -> pickFile(REQ_PICK + 1 + i, "*/*"))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pickFile(int request, String mimeType) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(mimeType);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, request);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req < REQ_PICK || req > REQ_PICK + MEDIA_TIMERS.length || res != RESULT_OK || data == null) return;
        TdApi.MessageSelfDestructType ttl = null;
        if (req != REQ_PICK) {
            int seconds = MEDIA_TIMERS[req - REQ_PICK - 1];
            ttl = seconds == 0 ? new TdApi.MessageSelfDestructTypeImmediately()
                    : new TdApi.MessageSelfDestructTypeTimer(seconds);
        }
        final Uri uri = data.getData();
        if (uri == null) return;
        sendPicked(uri, ttl);
    }

    private String displayNameOf(Uri uri) {
        String name = null;
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Throwable ignored) {
            // some providers refuse to answer; the fallback name is fine
        } finally {
            if (c != null) c.close();
        }
        if (name == null) {
            String last = uri.getLastPathSegment();
            name = last == null ? "upload" : last;
        }
        return name;
    }

    /**
     * Stages the file, shrinks it if it is a photo on a slow link, and sends.
     *
     * All of it happens off the UI thread: a 3 MP decode is seconds of work on
     * a Cortex-A7 and would freeze the compose bar.
     */
    private void sendPicked(final Uri uri, final TdApi.MessageSelfDestructType ttl) {
        final long attachedReply = replyMessageId;
        Toast.makeText(this, "Preparing\u2026", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    boolean slow = isSlowLink();
                    String mime = getContentResolver().getType(uri);
                    String name = displayNameOf(uri);
                    if (mime == null) {
                        int dot = name.lastIndexOf('.');
                        if (dot >= 0) mime = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.US));
                    }
                    UploadPrep.Kind kind = UploadPrep.kindOf(mime);
                    if (ttl != null && kind == UploadPrep.Kind.DOCUMENT) {
                        ui.post(() -> { if (!isFinishing()) ConversationActions.toast(ChatActivity.this, "Choose a photo or video."); });
                        return;
                    }

                    // Prefer the file that already exists; copy only when the
                    // provider has nothing behind the URI.
                    File staged = UploadPrep.resolvePath(ChatActivity.this, uri);
                    if (staged == null) {
                        staged = UploadPrep.stage(ChatActivity.this, uri, name);
                    }

                    File toSend = staged;
                    if (kind == UploadPrep.Kind.PHOTO && slow) {
                        toSend = UploadPrep.shrinkPhoto(ChatActivity.this, staged,
                                UploadPrep.MOBILE_MAX_PX, UploadPrep.MOBILE_QUALITY);
                    }

                    if (!staged.equals(toSend) && UploadPrep.isStaged(ChatActivity.this, staged)) {
                        UploadCleanup.discard(staged);
                    }

                    final String note = UploadPrep.estimate(toSend.length(), slow);
                    final int[] dimensions = kind == UploadPrep.Kind.PHOTO
                            ? UploadPrep.dimensionsOf(toSend) : null;
                    final File finalFile = toSend;
                    final UploadPrep.Kind finalKind = kind;

                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (note != null && !isFinishing()) {
                                Toast.makeText(ChatActivity.this,
                                        UploadPrep.sizeLabel(finalFile.length()) + " \u00b7 " + note,
                                        Toast.LENGTH_LONG).show();
                            }
                            sendFile(finalFile, finalKind, dimensions, attachedReply, ttl);
                        }
                    });
                } catch (final Exception t) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            Toast.makeText(ChatActivity.this,
                                    "Could not prepare file: " + t.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "cg-upload").start();
    }

    private void sendFile(File f, UploadPrep.Kind kind, int[] dim, long attachedReply, TdApi.MessageSelfDestructType ttl) {
        // Only a file we copied is ours to delete; a Gallery file is the user's.
        final File tracked = UploadPrep.isStaged(this, f) ? f : null;
        if (!maySend() || (ttl != null && (peerId == 0 || peerId == Td.me()))) {
            UploadCleanup.discard(tracked);
            if (!isFinishing()) Toast.makeText(this, R.string.forum_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        TdApi.InputFileLocal file = new TdApi.InputFileLocal(f.getPath());
        TdApi.InputMessageContent content;

        if (kind == UploadPrep.Kind.PHOTO) {
            TdApi.InputPhoto photo = new TdApi.InputPhoto();
            photo.photo = file;
            photo.width = dim == null ? 0 : dim[0];
            photo.height = dim == null ? 0 : dim[1];

            TdApi.InputMessagePhoto m = new TdApi.InputMessagePhoto();
            m.photo = photo;
            m.selfDestructType = ttl;
            m.caption = new TdApi.FormattedText("", null);
            content = m;

        } else if (kind == UploadPrep.Kind.VIDEO) {
            TdApi.InputVideo video = new TdApi.InputVideo();
            video.video = file;
            video.duration = 0;   // let TDLib read it from the container

            TdApi.InputMessageVideo m = new TdApi.InputMessageVideo();
            m.video = video;
            m.selfDestructType = ttl;
            m.caption = new TdApi.FormattedText("", null);
            content = m;

        } else {
            TdApi.InputDocument doc = new TdApi.InputDocument();
            doc.document = file;
            TdApi.InputMessageDocument m = new TdApi.InputMessageDocument();
            m.document = doc;
            m.caption = new TdApi.FormattedText("", null);
            content = m;
        }

        Td.send(new TdApi.SendMessage(chatId, topic(), reply(attachedReply),
                new TdApi.MessageSendOptions(), null, content),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object o) {
                        // The returned message carries the temporary id that
                        // UpdateMessageSendSucceeded will report as oldMessageId.
                        if (o instanceof TdApi.Message) {
                            ui.post(() -> { if (replyMessageId == attachedReply) clearReply(); });
                            UploadCleanup.track(chatId, ((TdApi.Message) o).id, tracked);
                        } else if (o instanceof TdApi.Error) {
                            UploadCleanup.discard(tracked);
                            final String error = ((TdApi.Error) o).message;
                            ui.post(new Runnable() {
                                @Override public void run() {
                                    if (!isFinishing()) Toast.makeText(ChatActivity.this, error,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                });
    }

    /**
     * True when the upload will go over the cellular link.
     *
     * This phone has no LTE; mobile data is EVDO Rev. A, about 100-150 kbps up.
     * WiFi is always treated as fast.
     */
    private boolean isSlowLink() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return false;
            int t = ni.getType();
            if (t == android.net.ConnectivityManager.TYPE_WIFI
                    || t == android.net.ConnectivityManager.TYPE_ETHERNET
                    || t == android.net.ConnectivityManager.TYPE_WIMAX) {
                return false;
            }
            return t == android.net.ConnectivityManager.TYPE_MOBILE;
        } catch (Throwable t) {
            return false;
        }
    }
}
