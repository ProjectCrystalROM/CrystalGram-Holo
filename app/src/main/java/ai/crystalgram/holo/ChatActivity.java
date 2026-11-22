package ai.crystalgram.holo;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

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

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
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
            row.bind(withTimestamp(text, time, outgoing, failed.contains(m.id)), outgoing);
            return row;
        }

        private String textOf(TdApi.Message m) {
            if (m.content instanceof TdApi.MessageText) {
                return ((TdApi.MessageText) m.content).text.text;
            }
            if (m.content instanceof TdApi.MessagePhoto) return "[Photo]";
            if (m.content instanceof TdApi.MessageVideo) return "[Video]";
            if (m.content instanceof TdApi.MessageSticker) return "[Sticker]";
            if (m.content instanceof TdApi.MessageVoiceNote) return "[Voice message]";
            if (m.content instanceof TdApi.MessageDocument) return "[Document]";
            return "[" + m.content.getClass().getSimpleName().replace("Message", "") + "]";
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

        Row(Context ctx) {
            super(ctx);
            setOrientation(HORIZONTAL);
            int margin = dp(6);
            setPadding(dp(8), margin, dp(8), margin);

            bubble = new TextView(ctx);
            bubble.setTextSize(15);
            bubble.setTextColor(Palette.BUBBLE_TEXT);
            bubble.setPadding(dp(11), dp(7), dp(11), dp(7));
            addView(bubble, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void bind(CharSequence content, boolean outgoing) {
            bubble.setText(content);
            ((LayoutParams) bubble.getLayoutParams()).gravity =
                    outgoing ? Gravity.END : Gravity.START;
            setGravity(outgoing ? Gravity.END : Gravity.START);
            bubble.setBackgroundDrawable(outgoing
                    ? BubbleDrawable.sent(density)
                    : BubbleDrawable.received(density));
        }
    }
}
