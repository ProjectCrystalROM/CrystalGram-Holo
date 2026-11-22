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

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(empty, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        Td.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 100));
        Td.send(new TdApi.GetChats(new TdApi.ChatListMain(), 100),
                new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object object) {
                        if (object instanceof TdApi.Chats) {
                            onChatsLoaded(((TdApi.Chats) object).chatIds);
                        } else if (object instanceof TdApi.Error) {
                            Log.w(TAG, "GetChats: " + ((TdApi.Error) object).message);
                        }
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
            Td.send(new TdApi.LogOut());
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
        for (long id : chatIds) ids.add(id);
        ui.post(new Runnable() {
            @Override public void run() { requestTitles(ids); }
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
        Td.send(new TdApi.GetChat(chatId));
    }

    /** Called from the TDLib update thread with a resolved chat. */
    void onChat(TdApi.Chat chat) {
        Log.i(TAG, "chat " + chat.id + " order=" + ChatRow.orderOf(chat)
                + " type=" + chat.type.getClass().getSimpleName()
                + " title=" + chat.title);
        final ChatRow row = ChatRow.from(chat);
        ui.post(new Runnable() {
            @Override public void run() {
                adapter.put(row);
                empty.setVisibility(adapter.getCount() == 0 ? View.VISIBLE : View.GONE);
                if (adapter.getCount() == 0) {
                    empty.setText("No chats yet.");
                }
            }
        });
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
            r.supported = isSupported(chat.type);
            r.preview = previewOf(chat.lastMessage);
            return r;
        }

        /** Order of this chat in the main list, or MAX_VALUE if not there. */
        static long orderOf(TdApi.Chat chat) {
            if (chat.positions == null) return Long.MAX_VALUE;
            for (TdApi.ChatPosition p : chat.positions) {
                if (p.list instanceof TdApi.ChatListMain) return p.order;
            }
            return Long.MAX_VALUE;
        }

        /**
         * Scope: private chats and groups only. Channels are hidden entirely.
         *
         * Telegram API ToS 3.3 requires a client that shows channels to render
         * official sponsored messages, and that obligation cannot be met by
         * omission. So instead of showing a channel with its sponsors stripped
         * out, channels are not offered at all: nothing is shown, so there is
         * nothing to strip. Sponsors are never relocated, restyled, hidden
         * inside a channel, or replaced with other content.
         */
        static boolean isSupported(TdApi.ChatType type) {
            boolean ok = type instanceof TdApi.ChatTypePrivate
                || type instanceof TdApi.ChatTypeSecret
                || type instanceof TdApi.ChatTypeBasicGroup
                || (type instanceof TdApi.ChatTypeSupergroup
                    && !((TdApi.ChatTypeSupergroup) type).isChannel);
            String kind = type == null ? "null" : type.getClass().getSimpleName();
            if (type instanceof TdApi.ChatTypeSupergroup) {
                kind += (((TdApi.ChatTypeSupergroup) type).isChannel ? "(channel)" : "(group)");
            }
            Log.i(TAG, "type " + kind + " supported=" + ok);
            return ok;
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

        void put(ChatRow row) {
            if (!row.supported) {
                // Drop unsupported chats (channels) from this stage's list.
                if (byId.remove(row.chatId) != null) {
                    rows.remove(findIndex(row.chatId));
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
         * descending Long.compare gives the order TDLib specifies.
         */
        private void sort() {
            java.util.Collections.sort(rows, new java.util.Comparator<ChatRow>() {
                @Override public int compare(ChatRow a, ChatRow b) {
                    int c = Long.compare(b.order, a.order);   // descending
                    if (c != 0) return c;
                    return Long.compare(b.chatId, a.chatId);  // tie-break, descending
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

    @Override
    protected void onResume() {
        super.onResume();
        // Visible chat list counts as present: TDLib may suppress notifications
        // while online, which is correct only while a screen is in front.
        Td.setOnline(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Td.setOnline(false);
    }
}
