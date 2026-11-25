package ai.crystalgram.holo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Topic list for an eligible group forum. */
public class ForumActivity extends Activity {
    private static final int PAGE_SIZE = 50;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer, TdApi.ForumTopic> topics = new HashMap<>();
    private ArrayAdapter<TdApi.ForumTopic> adapter;
    private TextView status;
    private Button more;
    private long chatId;
    private int generation;
    private boolean opened;
    private boolean loading;
    private boolean showHidden;
    private boolean paged;
    private int nextDate;
    private long nextMessage;
    private int nextTopic;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!loading) loadPage(true, false);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        chatId = getIntent().getLongExtra(ChatActivity.EXTRA_CHAT_ID, 0);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        status.setPadding(padding, padding, padding, padding);
        root.addView(status);
        ListView list = new ListView(this);
        more = new Button(this);
        more.setText(R.string.forum_more);
        more.setVisibility(View.GONE);
        more.setOnClickListener(v -> loadPage(false, false));
        list.addFooterView(more, null, false);
        adapter = new ArrayAdapter<TdApi.ForumTopic>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, new ArrayList<TdApi.ForumTopic>()) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View row = super.getView(position, recycled, parent);
                TdApi.ForumTopic topic = getItem(position);
                TextView title = (TextView) row.findViewById(android.R.id.text1);
                title.setText(topic.info.name);
                TextView detail = (TextView) row.findViewById(android.R.id.text2);
                StringBuilder label = new StringBuilder();
                if (topic.isPinned) label.append(getString(R.string.forum_pinned));
                if (topic.info.isClosed) appendLabel(label, getString(R.string.forum_closed));
                if (topic.info.isHidden) appendLabel(label, getString(R.string.forum_hidden));
                if (topic.unreadCount > 0) appendLabel(label, getResources().getQuantityString(
                        R.plurals.forum_unread, topic.unreadCount, topic.unreadCount));
                appendLabel(label, MainActivity.ChatRow.previewOf(topic.lastMessage));
                detail.setText(label);
                detail.setVisibility(label.length() == 0 ? View.GONE : View.VISIBLE);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (!opened || Td.scope(chatId) != ChatScope.FORUM || position >= adapter.getCount()) return;
            TdApi.ForumTopic topic = adapter.getItem(position);
            startActivity(new Intent(this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                    .putExtra(ChatActivity.EXTRA_TOPIC_ID, topic.info.forumTopicId));
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private static void appendLabel(StringBuilder label, String value) {
        if (value == null || value.length() == 0) return;
        if (label.length() != 0) label.append(" · ");
        label.append(value);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!Td.authorized() || chatId == 0) {
            finish();
            return;
        }
        status.setText(R.string.forum_loading);
        status.setVisibility(View.VISIBLE);
        final int request = ++generation;
        Td.resolveChat(chatId, chat -> ui.post(() -> {
            if (request != generation || isFinishing()) return;
            if (chat == null || Td.scope(chatId) != ChatScope.FORUM) {
                finish();
                return;
            }
            setTitle(chat.title);
            if (getActionBar() != null) getActionBar().setSubtitle(R.string.forum_topics);
            opened = true;
            Td.bindForum(this);
            Td.send(new TdApi.OpenChat(chatId));
            loadPage(true, true);
        }));
    }

    @Override protected void onPause() {
        generation++;
        loading = false;
        ui.removeCallbacksAndMessages(null);
        if (opened) {
            opened = false;
            Td.unbindForum(this);
            Td.send(new TdApi.CloseChat(chatId));
        }
        super.onPause();
    }

    void onTopicsChanged(long id) {
        if (id != chatId) return;
        ui.post(() -> {
            if (!opened || loading) return;
            ui.removeCallbacks(refresh);
            ui.postDelayed(refresh, 600);
        });
    }

    void onScopeChanged(long id) {
        if (id == chatId) ui.post(() -> {
            if (opened && Td.scope(chatId) != ChatScope.FORUM) finish();
        });
    }

    void onMessagesDeleted(TdApi.UpdateDeleteMessages update) {
        if (update.chatId != chatId || !update.isPermanent) return;
        ui.post(() -> {
            if (!opened) return;
            boolean changed = false;
            java.util.Iterator<TdApi.ForumTopic> iterator = topics.values().iterator();
            while (iterator.hasNext()) {
                TdApi.ForumTopic item = iterator.next();
                for (long id : update.messageIds) {
                    if (ChatScope.isDeletedTopicRoot(item.info, id)) {
                        iterator.remove();
                        changed = true;
                        break;
                    }
                }
            }
            if (changed) publish();
        });
    }

    private void loadPage(final boolean first, final boolean reset) {
        if (!opened || loading || Td.scope(chatId) != ChatScope.FORUM || (!first && nextTopic == 0)) return;
        loading = true;
        more.setEnabled(false);
        final int request = generation;
        final int date = first ? 0 : nextDate;
        final long message = first ? 0 : nextMessage;
        final int topic = first ? 0 : nextTopic;
        Td.send(new TdApi.GetForumTopics(chatId, "", 0, false, false, date, message, topic, PAGE_SIZE),
                result -> ui.post(() -> {
                    if (generation != request || !opened || isFinishing()) return;
                    loading = false;
                    more.setEnabled(true);
                    if (Td.scope(chatId) != ChatScope.FORUM) return;
                    if (result instanceof TdApi.Error) {
                        status.setText(((TdApi.Error) result).message);
                        status.setVisibility(View.VISIBLE);
                        return;
                    }
                    if (!(result instanceof TdApi.ForumTopics)) return;
                    TdApi.ForumTopics page = (TdApi.ForumTopics) result;
                    if (reset || (first && !paged)) topics.clear();
                    if (reset) paged = false;
                    if (!first) paged = true;
                    for (TdApi.ForumTopic item : page.topics) {
                        if (item.info != null && item.info.chatId == chatId && item.info.forumTopicId > 0) {
                            topics.put(item.info.forumTopicId, item);
                        }
                    }
                    if (!first || !paged) {
                        nextDate = page.nextOffsetDate;
                        nextMessage = page.nextOffsetMessageId;
                        nextTopic = page.nextOffsetForumTopicId;
                        if (date == nextDate && message == nextMessage && topic == nextTopic) nextTopic = 0;
                    }
                    publish();
                }));
    }

    private void publish() {
        ArrayList<TdApi.ForumTopic> rows = new ArrayList<>();
        for (TdApi.ForumTopic topic : topics.values()) {
            if (showHidden || !topic.info.isHidden) rows.add(topic);
        }
        Collections.sort(rows, new Comparator<TdApi.ForumTopic>() {
            @Override public int compare(TdApi.ForumTopic left, TdApi.ForumTopic right) {
                if (left.order != right.order) return left.order > right.order ? -1 : 1;
                int a = left.info.forumTopicId;
                int b = right.info.forumTopicId;
                return a == b ? 0 : (a > b ? -1 : 1);
            }
        });
        adapter.setNotifyOnChange(false);
        adapter.clear();
        adapter.addAll(rows);
        adapter.notifyDataSetChanged();
        status.setText(R.string.forum_empty);
        status.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        more.setVisibility(nextTopic == 0 ? View.GONE : View.VISIBLE);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.forum_refresh);
        menu.add(0, 2, 1, R.string.forum_show_hidden).setCheckable(true).setChecked(showHidden);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == 1) {
            loadPage(true, true);
            return true;
        }
        if (item.getItemId() == 2) {
            showHidden = !showHidden;
            item.setChecked(showHidden);
            publish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
