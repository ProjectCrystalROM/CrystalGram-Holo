package ai.crystalgram.holo;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import android.util.Log;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat list, driven by TDLib.
 *
 * Scope for this stage: private chats (1-1) and basic/supergroups (GCs).
 * Channels are deliberately excluded until the sponsorship work lands -- the
 * filter lives in ChatListAdapter.isSupported() so it is one obvious place.
 */
public class MainActivity extends android.app.Activity {

    private static final String TAG = "CG-ChatList";

    private ListView list;
    private TextView empty;
    private ChatListAdapter adapter;
    private boolean destroyed;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.app.ActionBar bar = getActionBar();
        if (bar != null) bar.setTitle("CrystalGram");

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        adapter = new ChatListAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View v, int position, long id) {
                ChatRow row = adapter.getItem(position);
                Intent i = new Intent(MainActivity.this, ChatActivity.class);
                i.putExtra(ChatActivity.EXTRA_CHAT_ID, row.chatId);
                i.putExtra(ChatActivity.EXTRA_TITLE, row.title);
                startActivity(i);
            }
        });

        empty = new TextView(this);
        empty.setText("Loading chats\u2026");
        empty.setTextSize(14);
        empty.setPadding(dp(20), dp(20), dp(20), dp(20));
        empty.setVisibility(View.VISIBLE);

        android.widget.FrameLayout stack = new android.widget.FrameLayout(this);
        stack.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stack.addView(empty, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Banner keeps the connection state visible instead of leaving a blank
        // list that looks like there are no chats.
        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        contentRoot.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(contentRoot);

    }

    /**
     * Cache first, network second.
     *
     * LoadChats asks TDLib to fetch the list from the server, which needs a
     * connection and can take seconds. GetChats answers straight from the local
     * database. Calling the cache read first paints the chats immediately, so a
     * cold start on a slow or missing network shows what was there last time
     * instead of an empty screen, and the network result simply refines it.
     *
     * The order matters: GetChats must not be skipped when LoadChats is slow,
     * and LoadChats must still be issued so the list is current.
     */
    private void loadChatsFromCacheThenNetwork() {
        Td.send(new TdApi.GetChats(new TdApi.ChatListMain(), 100),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object object) {
                        if (object instanceof TdApi.Chats) {
                            onChatsLoaded(((TdApi.Chats) object).chatIds);
                        }
                    }
                });

        Td.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 100),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object object) {
                        // 404 means the chat list is already fully loaded, which
                        // is not an error worth showing anyone.
                        if (object instanceof TdApi.Error) {
                            TdApi.Error e = (TdApi.Error) object;
                            if (e.code != 404) Log.w(TAG, "LoadChats: " + e.message);
                            return;
                        }
                        Td.send(new TdApi.GetChats(new TdApi.ChatListMain(), 100),
                                new Client.ResultHandler() {
                                    @Override public void onResult(TdApi.Object o) {
                                        if (o instanceof TdApi.Chats) {
                                            onChatsLoaded(((TdApi.Chats) o).chatIds);
                                        }
                                    }
                                });
                    }
                });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Refresh");
        menu.add(0, 2, 1, "Log out");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            Td.send(new TdApi.GetChats(new TdApi.ChatListMain(), 100),
                    new Client.ResultHandler() {
                        @Override public void onResult(TdApi.Object object) {
                            if (object instanceof TdApi.Chats) {
                                onChatsLoaded(((TdApi.Chats) object).chatIds);
                            }
                        }
                    });
            return true;
        }
        if (item.getItemId() == 2) {
            Td.beginLogout();
            Td.send(new TdApi.LogOut(), new Client.ResultHandler() {
                @Override public void onResult(TdApi.Object result) {
                    if (result instanceof TdApi.Error) Td.onLogoutError((TdApi.Error) result);
                }
            });
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------------------------------------------------------------- TDLib -> UI

    /** Called from the TDLib update thread once GetChats returns. */
    void onChatsLoaded(long[] chatIds) {
        Log.i(TAG, "GetChats returned " + (chatIds == null ? 0 : chatIds.length));
        final List<Long> ids = new ArrayList<Long>();
        if (chatIds != null) for (long id : chatIds) ids.add(id);
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                adapter.retain(ids);
                requestTitles(ids);
                updateEmptyLabel();
            }
        });
    }

    /** Called from the TDLib update thread when one chat changes. */
    void onChatUpdated(long chatId) {
        ui.post(new Runnable() {
            @Override public void run() { refreshOne(chatId); }
        });
    }

    private void requestTitles(List<Long> ids) {
        Log.i(TAG, "requesting " + ids.size() + " chats");
        for (long id : ids) {
            Td.send(new TdApi.GetChat(id), new Client.ResultHandler() {
                @Override public void onResult(TdApi.Object object) {
                    if (object instanceof TdApi.Chat) {
                        Log.i(TAG, "GetChat -> " + ((TdApi.Chat) object).id);
                        onChat((TdApi.Chat) object);
                    } else if (object instanceof TdApi.Error) {
                        TdApi.Error e = (TdApi.Error) object;
                        Log.w(TAG, "GetChat failed " + e.code + ": " + e.message);
                    }
                }
            });
        }
    }

    private void refreshOne(long chatId) {
        requestTitles(java.util.Collections.singletonList(chatId));
    }

    /** Called from the TDLib update thread with a resolved chat. */
    void onChat(TdApi.Chat chat) {

        Td.cacheChat(chat);
        final ChatRow row = ChatRow.from(chat);
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                adapter.put(row);
                updateEmptyLabel();
            }
        });
    }

    private void updateEmptyLabel() {
        boolean noChats = adapter.getCount() == 0;
        empty.setVisibility(noChats ? View.VISIBLE : View.GONE);
        if (noChats) empty.setText("No chats yet.");
    }

    // ------------------------------------------------------------------- adapter

    static class ChatRow {
        long chatId;
        String title;
        String preview;
        boolean supported;
        long order;

        static ChatRow from(TdApi.Chat chat) {
            ChatRow r = new ChatRow();
            r.chatId = chat.id;
            r.title = chat.title;
            r.order = orderOf(chat);
            r.supported = Td.scope(chat) != ChatScope.EXCLUDED;
            r.preview = previewOf(chat.lastMessage);
            return r;
        }

        /** Order of this chat in the main list, or MAX_VALUE if not there. */
        static long orderOf(TdApi.Chat chat) {
            if (chat.positions == null) return 0L;
            for (TdApi.ChatPosition p : chat.positions) {
                if (p.list instanceof TdApi.ChatListMain) return p.order;
            }
            return 0L;
        }

        static String previewOf(TdApi.Message m) {
            if (m == null || m.content == null) return "";
            TdApi.MessageContent c = m.content;
            if (c instanceof TdApi.MessageText) {
                return ((TdApi.MessageText) c).text.text;
            }
            if (c instanceof TdApi.MessagePhoto) return "Photo";
            if (c instanceof TdApi.MessageVideo) return "Video";
            if (c instanceof TdApi.MessageSticker) return "Sticker";
            if (c instanceof TdApi.MessageVoiceNote) return "Voice message";
            if (c instanceof TdApi.MessageDocument) return "Document";
            return "";
        }
    }

    private class ChatListAdapter extends BaseAdapter {
        private final List<ChatRow> rows = new ArrayList<ChatRow>();
        private final Map<Long, ChatRow> byId = new HashMap<Long, ChatRow>();

        void retain(List<Long> ids) {
            java.util.HashSet<Long> current = new java.util.HashSet<>(ids);
            java.util.Iterator<ChatRow> iterator = rows.iterator();
            boolean changed = false;
            while (iterator.hasNext()) {
                long id = iterator.next().chatId;
                if (!current.contains(id)) {
                    iterator.remove();
                    byId.remove(id);
                    changed = true;
                }
            }
            if (changed) notifyDataSetChanged();
        }

        void put(ChatRow row) {
            if (!row.supported || row.order == 0) {
                // Drop unsupported chats (channels) from this stage's list.
                if (byId.remove(row.chatId) != null) {
                    rows.remove(findIndex(row.chatId));
                    notifyDataSetChanged();
                }
                return;
            }
            ChatRow existing = byId.get(row.chatId);
            if (existing != null) {
                existing.title = row.title;
                existing.preview = row.preview;
                existing.order = row.order;
            } else {
                byId.put(row.chatId, row);
                rows.add(row);
            }
            sort();
            notifyDataSetChanged();
        }

        private int findIndex(long chatId) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).chatId == chatId) return i;
            }
            return -1;
        }

        /**
         * td_api.tl, chatPosition:
         *   "Chats must be sorted by the pair (order, chat.id) in descending order"
         *
         * No unsigned transform: order values are positive here, and flipping
         * the sign bit on a positive long inverts the comparison. A plain
         * descending comparison gives the order TDLib specifies.
         */
        private void sort() {
            java.util.Collections.sort(rows, new java.util.Comparator<ChatRow>() {
                @Override public int compare(ChatRow a, ChatRow b) {
                    int c = compareDescending(a.order, b.order);   // descending
                    if (c != 0) return c;
                    return compareDescending(a.chatId, b.chatId);  // tie-break, descending
                }
            });
        }

        @Override public int getCount() { return rows.size(); }
        @Override public ChatRow getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return rows.get(i).chatId; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView instanceof TextView) {
                tv = (TextView) convertView;
            } else {
                tv = new TextView(MainActivity.this);
                tv.setTextSize(15);
                tv.setPadding(dp(16), dp(12), dp(16), dp(12));
                tv.setLineSpacing(dp(2), 1f);
            }
            ChatRow r = rows.get(position);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
            sb.append(r.title);
            if (r.preview.length() > 0) {
                sb.append("\n");
                int start = sb.length();
                sb.append(r.preview);
                sb.setSpan(new android.text.style.RelativeSizeSpan(0.8f), start, sb.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new android.text.style.ForegroundColorSpan(Palette.PREVIEW), start,
                        sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tv.setText(sb);
            return tv;
        }
    }

    private static int compareDescending(long left, long right) {
        if (left == right) return 0;
        return left > right ? -1 : 1;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Td.authorized()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        Td.bindChatList(this);
        Td.setOnline(true);
        Td.setConnectionListener(new Td.ConnectionListener() {
            @Override public void onConnectionState(final TdApi.ConnectionState state) {
                ui.post(new Runnable() {
                    @Override public void run() { if (!isFinishing()) showConnState(state); }
                });
            }
        });
        if (Td.connectionState() != null) showConnState(Td.connectionState());
        loadChatsFromCacheThenNetwork();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        Td.unbindChatList(this);
        Td.setOnline(false);
        Td.setConnectionListener(null);
        super.onPause();
    }

    private TextView connBanner;
    /** Vertical container holding the banner above the list. */
    private LinearLayout contentRoot;

    private void ensureBanner() {
        if (connBanner != null) return;
        if (contentRoot == null) return;
        connBanner = new TextView(this);
        connBanner.setTextSize(13);
        connBanner.setTextColor(0xFFFFFFFF);
        connBanner.setBackgroundColor(0xFF3A3A3A);
        connBanner.setPadding(dp(12), dp(6), dp(12), dp(6));
        connBanner.setVisibility(View.GONE);
        contentRoot.addView(connBanner, 0);
    }

    private void showConnState(TdApi.ConnectionState st) {
        if (st instanceof TdApi.ConnectionStateReady) {
            hideConnBanner();
            return;
        }
        ensureBanner();
        String text;
        if (st instanceof TdApi.ConnectionStateWaitingForNetwork) text = "Waiting for network\u2026";
        else if (st instanceof TdApi.ConnectionStateConnecting) text = "Connecting\u2026";
        else if (st instanceof TdApi.ConnectionStateConnectingToProxy) text = "Connecting to proxy\u2026";
        else if (st instanceof TdApi.ConnectionStateUpdating) text = "Updating\u2026";
        else text = "Connecting\u2026";
        connBanner.setText(text);
        connBanner.setVisibility(View.VISIBLE);
    }

    private void hideConnBanner() {
        if (connBanner != null) connBanner.setVisibility(View.GONE);
    }

}
