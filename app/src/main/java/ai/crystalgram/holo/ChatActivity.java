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
        String title = getIntent().getStringExtra(EXTRA_TITLE);

        android.app.ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setTitle(title == null ? "Chat" : title);
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
        send.setText("Send");
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendMessage(); }
        });
        entry.addView(send);
        root.addView(entry);

        // Keep the keyboard down until the user taps the field.
        input.clearFocus();
        list.requestFocus();

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
        resumed = true;
        Td.bindConversation(this);
        Td.setOnline(true);
        Td.send(new TdApi.OpenChat(chatId));
        NotificationService.setOpenChat(chatId);
        MediaLoader.get().addListener(mediaListener);
        startNetworkWatch();
        loadHistory(0);
    }

    @Override
    protected void onPause() {
        if (resumed) {
            resumed = false;
            historyGeneration++;
            historyLoading = false;
            Td.setConnectionListener(null);
            MediaLoader.get().removeListener(mediaListener);
            NotificationService.setOpenChat(0);
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
        if (!resumed || historyLoading) return;
        historyLoading = true;
        final int generation = historyGeneration;
        Td.send(new TdApi.GetChatHistory(chatId, from, 0, 50, false),
                new Client.ResultHandler() {
                    @Override public void onResult(final TdApi.Object result) {
                        ui.post(new Runnable() {
                            @Override public void run() {
                                if (generation != historyGeneration || !resumed) return;
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

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, 3, 0, "Attach");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 3) { pickFile(); return true; }
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void sendMessage() {
        String text = input.getText().toString();
        if (sendingText || text.trim().length() == 0) return;
        sendingText = true;
        TdApi.InputMessageText content = new TdApi.InputMessageText();
        content.text = new TdApi.FormattedText(text, null);
        final String draft = text;
        Td.send(new TdApi.SendMessage(chatId, null, null,
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
                        }
                    }
                });
            }
        });
    }

    // -------------------------------------------------------- TDLib -> UI hooks

    /** UpdateChatAction: someone else started or stopped typing. */
    void onChatAction(long id, final TdApi.MessageSender sender, final TdApi.ChatAction action) {
        if (id != chatId) return;
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
        if (message.chatId != chatId) return;
        ui.post(new Runnable() {
            @Override public void run() {
                boolean atBottom = adapter.getCount() == 0
                        || list.getLastVisiblePosition() == adapter.getCount() - 1;
                adapter.upsert(message);
                if (resumed && (atBottom || message.isOutgoing)) scrollToBottom();
            }
        });
    }

    void onMessageSent(final TdApi.Message message, final long oldMessageId) {
        if (message.chatId != chatId) return;
        ui.post(new Runnable() {
            @Override public void run() { adapter.replaceById(oldMessageId, message); }
        });
    }

    void onMessageFailed(final TdApi.Message message, final long oldMessageId,
                         final TdApi.Error error) {
        if (message.chatId != chatId) return;
        ui.post(new Runnable() {
            @Override public void run() {
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
                    for (long id : update.messageIds) adapter.deleted.add(id);
                }
                Set<Long> removed = new HashSet<>();
                for (long id : update.messageIds) removed.add(id);
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
        if (!resumed) return;
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
        Td.send(new TdApi.SendChatAction(chatId, null, null, action));
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
                @Override public void run() { if (resumed) adapter.notifyDataSetChanged(); }
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
        Td.send(new TdApi.ViewMessages(chatId, arr, null, false));
    }

    private long lastViewedIncomingId;

    private void markVisibleAsRead() {
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
        private final List<TdApi.Message> items = new ArrayList<TdApi.Message>();
        private final Set<Long> failed = new HashSet<Long>();

        int mergeHistory(TdApi.Message[] page) {
            if (page == null) return 0;
            Set<Long> known = new HashSet<>();
            for (TdApi.Message message : items) known.add(message.id);
            int before = items.size();
            for (TdApi.Message message : page) {
                if (message != null && !deleted.contains(message.id) && known.add(message.id)) items.add(message);
            }
            if (items.size() != before) publish();
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
            items.add(message);
            publish();
        }

        void markFailed(long id, String reason) {
            failed.add(id);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return items.get(i).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = (convertView instanceof Row) ? (Row) convertView : new Row(ChatActivity.this);
            TdApi.Message m = items.get(position);
            boolean outgoing = m.isOutgoing;
            String text = textOf(m);
            String time = timeOf(m);
            MediaVm media = MediaVm.of(m, MediaLoader.get());
            row.bind(withTimestamp(text, time, outgoing, failed.contains(m.id) || m.sendingState instanceof TdApi.MessageSendingStateFailed),
                    outgoing, media, iconFor(m));
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
            if (m.content instanceof TdApi.MessageVoiceNote) return "";
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
     * the tail side. Failed sends get a marker appended.
     */
    private CharSequence withTimestamp(String text, String time, boolean outgoing, boolean failed) {
        String tail = time + (failed ? "  \u26a0 not sent" : "");
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
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        sendPicked(uri);
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
    private void sendPicked(final Uri uri) {
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
                            sendFile(finalFile, finalKind, dimensions);
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

    private void sendFile(File f, UploadPrep.Kind kind, int[] dim) {
        // Only a file we copied is ours to delete; a Gallery file is the user's.
        final File tracked = UploadPrep.isStaged(this, f) ? f : null;
        TdApi.InputFileLocal file = new TdApi.InputFileLocal(f.getPath());
        TdApi.InputMessageContent content;

        if (kind == UploadPrep.Kind.PHOTO) {
            TdApi.InputPhoto photo = new TdApi.InputPhoto();
            photo.photo = file;
            photo.width = dim == null ? 0 : dim[0];
            photo.height = dim == null ? 0 : dim[1];

            TdApi.InputMessagePhoto m = new TdApi.InputMessagePhoto();
            m.photo = photo;
            m.caption = new TdApi.FormattedText("", null);
            content = m;

        } else if (kind == UploadPrep.Kind.VIDEO) {
            TdApi.InputVideo video = new TdApi.InputVideo();
            video.video = file;
            video.duration = 0;   // let TDLib read it from the container

            TdApi.InputMessageVideo m = new TdApi.InputMessageVideo();
            m.video = video;
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

        Td.send(new TdApi.SendMessage(chatId, null, null,
                new TdApi.MessageSendOptions(), null, content),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object o) {
                        // The returned message carries the temporary id that
                        // UpdateMessageSendSucceeded will report as oldMessageId.
                        if (o instanceof TdApi.Message && tracked != null) {
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
