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
    private NetworkMonitor net;
    /** The activity root, kept so nothing has to search for it. */
    private LinearLayout layoutRoot;
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
        layoutRoot = root;

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

        new Thread(new Runnable() {
            @Override public void run() {
                UploadCleanup.sweep(new File(getCacheDir(), "outgoing"));
            }
        }, "cg-sweep").start();

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

        Td.send(new TdApi.OpenChat(chatId));
        NotificationService.setOpenChat(chatId);
        MediaLoader.get().addListener(mediaListener);
        loadNewest();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Td.bindConversation(this);
    }

    @Override
    protected void onPause() {
        Td.unbindConversation(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Td.send(new TdApi.CloseChat(chatId));
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * density);
    }

    /**
     * Newest page. Per td_api.tl: "use 0 to get results from the last message".
     */
    private void loadNewest() {
        Td.send(new TdApi.GetChatHistory(chatId, 0L, 0, 50, false), historyHandler(true));
    }

    /**
     * One page further back. from_message_id is exclusive going backwards, so
     * asking from the oldest id we hold returns the messages before it.
     */
    private void loadOlder() {
        if (oldestMessageId == 0) return;
        final long from = oldestMessageId;
        oldestMessageId = 0;   // guard against repeat requests while in flight
        Td.send(new TdApi.GetChatHistory(chatId, from, 0, 50, false), historyHandler(false));
    }

    private Client.ResultHandler historyHandler(final boolean replace) {
        return new Client.ResultHandler() {
            @Override public void onResult(TdApi.Object object) {
                if (object instanceof TdApi.Messages) {
                    TdApi.Messages m = (TdApi.Messages) object;
                    Log.i(TAG, (replace ? "history" : "older") + ": total=" + m.totalCount
                            + " returned=" + (m.messages == null ? 0 : m.messages.length));
                    if (m.messages == null || m.messages.length == 0) {
                        oldestMessageId = 0;
                        return;
                    }
                    if (replace) onHistory(chatId, m.messages);
                    else onOlderHistory(chatId, m.messages);
                } else if (object instanceof TdApi.Error) {
                    TdApi.Error e = (TdApi.Error) object;
                    Log.w(TAG, "GetChatHistory failed: " + e.message);
                    oldestMessageId = 0;
                }
            }
        };
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
        String text = input.getText().toString().trim();
        if (text.length() == 0) return;
        TdApi.InputMessageText content = new TdApi.InputMessageText();
        content.text = new TdApi.FormattedText(text, null);
        Td.send(new TdApi.SendMessage(chatId, null, null,
                new TdApi.MessageSendOptions(), null, content));
        input.setText("");
    }

    // -------------------------------------------------------- TDLib -> UI hooks

    void onHistory(long id, TdApi.Message[] messages) {
        if (id != chatId || messages == null) return;
        final List<TdApi.Message> batch = new ArrayList<TdApi.Message>();
        for (TdApi.Message m : messages) batch.add(m);
        ui.post(new Runnable() {
            @Override public void run() {
                adapter.setHistory(batch);
                scrollToBottom();
                // The newest page was just opened, so the user has seen it.
                // Do not consult the ListView here: it has not laid out yet
                // (getLastVisiblePosition() == -1) in this same pass.
                markMessagesAsRead(batch);
            }
        });
    }

    /** UpdateChatAction: someone else started or stopped typing. */
    void onChatAction(final TdApi.MessageSender sender, final TdApi.ChatAction action) {
        ui.post(new Runnable() {
            @Override public void run() {
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

    void onOlderHistory(long id, TdApi.Message[] messages) {
        if (id != chatId || messages == null || messages.length == 0) return;
        final List<TdApi.Message> batch = new ArrayList<TdApi.Message>();
        for (TdApi.Message m : messages) batch.add(m);
        ui.post(new Runnable() {
            @Override public void run() {
                adapter.prependOlder(batch);
            }
        });
    }

    void onNewMessage(final TdApi.Message message) {
        if (message.chatId != chatId) return;
        ui.post(new Runnable() {
            @Override public void run() {
                adapter.upsert(message);
                scrollToBottom();
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

    /**
     * Telegram API ToS 1.4 forbids interfering with typing status, in either
     * direction: we must report our own typing, and we must show others'.
     *
     * TDLib throttles ChatActionTyping server-side (a notification is valid for
     * ~5s), so this repeats at most every 4s while the draft is non-empty, and
     * clears it as soon as the draft empties.
     */
    private void onDraftChanged(boolean hasText) {
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
                @Override public void run() { adapter.notifyDataSetChanged(); }
            });
        }
    };

    /** TDLib told us a file changed; decode it if it is now local. */
    void onFileUpdated(final TdApi.File file) {
        if (file == null || file.local == null) return;
        if (!file.local.isDownloadingCompleted) return;
        MediaLoader.get().decodeAsync(file, dp(320));
    }

    private void scrollToBottom() {
        if (adapter.getCount() > 0) list.setSelection(adapter.getCount() - 1);
        markVisibleAsRead();
    }

    /**
     * Telegram API ToS 1.4 forbids tampering with read statuses -- the client
     * must report what the user has actually seen. TDLib only marks messages
     * read once it is told they were viewed; OpenChat alone is not enough.
     *
     * The messages currently on screen are the newest ones, so this sends their
     * ids with force_read = false, letting TDLib decide based on chat state.
     */
    /** Marks an explicit batch as read, regardless of layout state. */
    private void markMessagesAsRead(List<TdApi.Message> messages) {
        List<Long> ids = new ArrayList<Long>();
        for (TdApi.Message m : messages) {
            if (m != null && !m.isOutgoing) ids.add(m.id);
        }
        sendViewMessages(ids);
    }

    private void sendViewMessages(List<Long> ids) {
        if (ids.isEmpty()) return;
        Log.i(TAG, "ViewMessages: " + ids.size() + " ids");
        long[] arr = new long[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        Td.send(new TdApi.ViewMessages(chatId, arr, null, false));
    }

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
        sendViewMessages(ids);
    }

    // ------------------------------------------------------------------ adapter

    private class MessageAdapter extends BaseAdapter {
        private final List<TdApi.Message> items = new ArrayList<TdApi.Message>();
        private final Set<Long> failed = new HashSet<Long>();

        void setHistory(List<TdApi.Message> history) {
            items.clear();
            failed.clear();
            // TDLib returns newest first; the list wants oldest first.
            for (int i = history.size() - 1; i >= 0; i--) items.add(history.get(i));
            updateOldest();
            notifyDataSetChanged();
        }

        /** Older page: TDLib returns newest-first, so reverse and prepend. */
        void prependOlder(List<TdApi.Message> older) {
            java.util.HashSet<Long> have = new java.util.HashSet<Long>();
            for (TdApi.Message m : items) have.add(m.id);
            int added = 0;
            for (int i = older.size() - 1; i >= 0; i--) {
                TdApi.Message m = older.get(i);
                if (!have.contains(m.id)) {
                    items.add(0, m);
                    added++;
                }
            }
            updateOldest();
            if (added > 0) notifyDataSetChanged();
        }

        private void updateOldest() {
            oldestMessageId = 0;
            for (TdApi.Message m : items) {
                if (oldestMessageId == 0 || m.id < oldestMessageId) oldestMessageId = m.id;
            }
        }

        void upsert(TdApi.Message m) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id == m.id) {
                    items.set(i, m);
                    notifyDataSetChanged();
                    return;
                }
            }
            items.add(m);
            notifyDataSetChanged();
        }

        /**
         * A sent message changes id when the server acknowledges it:
         * SendMessage/UpdateNewMessage carry a temporary id, then
         * UpdateMessageSendSucceeded reports the real one via oldMessageId.
         * Without this the same text renders twice.
         */
        void replaceById(long oldId, TdApi.Message m) {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id == oldId) {
                    items.set(i, m);
                    notifyDataSetChanged();
                    return;
                }
            }
            upsert(m);
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
            row.bind(withTimestamp(text, time, outgoing, failed.contains(m.id)),
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

            if (media.state == MediaVm.DOWNLOADING) {
                progress.setVisibility(VISIBLE);
                progress.setIndeterminate(false);
                progress.setProgress(media.percent());
            } else if (media.state == MediaVm.PENDING) {
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
        if (net == null) net = new NetworkMonitor(this);
        net.start(new NetworkMonitor.Listener() {
            @Override public void onNetworkAvailable(boolean available, TdApi.NetworkType type) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (available && Td.isReady()) hideConnBanner();
                    }
                });
            }
        });
        Td.setConnectionListener(new Td.ConnectionListener() {
            @Override public void onConnectionState(final TdApi.ConnectionState st) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { showConnState(st); }
                });
            }
        });
        if (Td.connectionState() != null) showConnState(Td.connectionState());
    }

    private void showConnState(TdApi.ConnectionState st) {
        if (st instanceof TdApi.ConnectionStateReady) {
            hideConnBanner();
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

    private void hideConnBanner() {
        connBanner.setVisibility(View.GONE);
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
        } catch (Throwable t) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        final String mime = getContentResolver().getType(uri);
        String name = displayNameOf(uri);
        sendPicked(uri, mime, name);
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
    private void sendPicked(final Uri uri, final String mime, final String name) {
        final boolean slow = isSlowLink();
        Toast.makeText(this, "Preparing\u2026", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    UploadPrep.Kind kind = UploadPrep.kindOf(mime);

                    // Prefer the file that already exists; copy only when the
                    // provider has nothing behind the URI.
                    File staged = UploadPrep.resolvePath(ChatActivity.this, uri);
                    if (staged == null) {
                        staged = UploadPrep.stage(ChatActivity.this, uri, name);
                    }

                    File toSend = staged;
                    if (kind == UploadPrep.Kind.PHOTO && slow) {
                        toSend = UploadPrep.shrinkPhoto(staged,
                                UploadPrep.MOBILE_MAX_PX, UploadPrep.MOBILE_QUALITY);
                    }

                    final String note = UploadPrep.estimate((int) toSend.length(), slow);
                    final File finalFile = toSend;
                    final UploadPrep.Kind finalKind = kind;
                    final String finalName = name;
                    final String finalMime = mime;

                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (note != null) {
                                Toast.makeText(ChatActivity.this,
                                        UploadPrep.sizeLabel(finalFile.length()) + " \u00b7 " + note,
                                        Toast.LENGTH_LONG).show();
                            }
                            sendFile(finalFile, finalKind, finalName, finalMime);
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            Toast.makeText(ChatActivity.this,
                                    "Could not prepare file: " + t.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "cg-upload").start();
    }

    private void sendFile(File f, UploadPrep.Kind kind, String name, String mime) {
        // Only a file we copied is ours to delete; a Gallery file is the user's.
        final File tracked = f.getPath().contains("/outgoing/") ? f : null;
        TdApi.InputFileLocal file = new TdApi.InputFileLocal(f.getPath());
        TdApi.InputMessageContent content;

        if (kind == UploadPrep.Kind.PHOTO) {
            TdApi.InputPhoto photo = new TdApi.InputPhoto();
            photo.photo = file;
            int[] dim = UploadPrep.dimensionsOf(f);
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
                            UploadCleanup.track(((TdApi.Message) o).id, tracked);
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
