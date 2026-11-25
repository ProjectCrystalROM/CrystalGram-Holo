package ai.crystalgram.holo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;

import org.drinkless.tdlib.TdApi;

import java.util.LinkedHashMap;
import java.util.Map;

final class ChatSettings {
    private ChatSettings() {}

    static void show(Activity activity, long chatId, TdApi.ChatList list, Runnable changed) {
        Td.resolveChat(chatId, chat -> activity.runOnUiThread(() -> {
            if (!ConversationActions.active(activity) || chat == null || Td.scope(chatId) == ChatScope.EXCLUDED) return;
            Map<String, Runnable> menu = new LinkedHashMap<>();
            menu.put("Notifications", () -> notifications(activity, chat, changed));
            TdApi.ChatPosition position = position(chat, list);
            if (position == null) {
                TdApi.ChatList alternative = list instanceof TdApi.ChatListArchive
                        ? new TdApi.ChatListMain() : new TdApi.ChatListArchive();
                listActions(activity, chat, alternative, menu, changed);
            } else {
                listActions(activity, chat, list, menu, changed);
            }
            if (!(chat.type instanceof TdApi.ChatTypeSecret)) {
                menu.put("Auto-delete messages", () -> autoDelete(activity, chat, changed));
            }
            if (chat.type instanceof TdApi.ChatTypePrivate) {
                long peer = ((TdApi.ChatTypePrivate) chat.type).userId;
                if (peer != Td.me()) {
                    boolean blocked = chat.blockList instanceof TdApi.BlockListMain;
                    String label = blocked ? "Unblock user" : "Block user";
                    menu.put(label, () -> ConversationActions.confirm(activity, label + "?", () ->
                            update(activity, chat.id, new TdApi.SetMessageSenderBlockList(
                                    new TdApi.MessageSenderUser(peer), blocked ? null : new TdApi.BlockListMain()), changed)));
                }
            } else if (chat.type instanceof TdApi.ChatTypeSupergroup) {
                TdApi.Supergroup group = Td.supergroup(chat);
                if (group != null && group.status instanceof TdApi.ChatMemberStatusLeft) {
                    menu.put("Join group", () -> join(activity, chat));
                } else if (group != null && !(group.status instanceof TdApi.ChatMemberStatusBanned)) {
                    menu.put("Leave group", () -> leave(activity, chat, changed));
                }
            } else if (chat.type instanceof TdApi.ChatTypeBasicGroup && position != null) {
                menu.put("Leave group", () -> leave(activity, chat, changed));
            }

            String[] labels = menu.keySet().toArray(new String[0]);
            new AlertDialog.Builder(activity).setTitle(Td.chatTitle(chat)).setItems(labels, (d, i) -> {
                if (eligible(activity, chat.id)) menu.get(labels[i]).run();
            }).show();
        }));
    }

    private static void listActions(Activity activity, TdApi.Chat chat, TdApi.ChatList list,
                                    Map<String, Runnable> menu, Runnable changed) {
        TdApi.ChatPosition position = position(chat, list);
        if (position == null) return;
        boolean archive = list instanceof TdApi.ChatListArchive;
        menu.put(archive ? "Unarchive" : "Archive", () -> update(activity, chat.id,
                new TdApi.AddChatToList(chat.id, archive ? new TdApi.ChatListMain() : new TdApi.ChatListArchive()),
                changed));
        menu.put(position.isPinned ? "Unpin chat" : "Pin chat", () -> update(activity, chat.id,
                new TdApi.ToggleChatIsPinned(list, chat.id, !position.isPinned), changed));
    }

    static TdApi.ChatPosition position(TdApi.Chat chat, TdApi.ChatList list) {
        if (chat.positions == null) return null;
        for (TdApi.ChatPosition position : chat.positions) {
            if (position.list != null && position.list.getConstructor() == list.getConstructor()
                    && position.order != 0) return position;
        }
        return null;
    }

    private static void notifications(Activity activity, TdApi.Chat chat, Runnable changed) {
        String[] options = {"Unmute", "Mute for 1 hour", "Mute for 1 day", "Mute indefinitely", "Use chat defaults"};
        int[] durations = {0, 3600, 86400, Integer.MAX_VALUE, 0};
        new AlertDialog.Builder(activity).setTitle("Notifications").setItems(options, (d, i) -> {
            if (!eligible(activity, chat.id)) return;
            // Read the current settings again so a remote change to sound or previews is preserved.
            Td.resolveChat(chat.id, current -> activity.runOnUiThread(() -> {
                if (current == null || !eligible(activity, chat.id) || current.notificationSettings == null) return;
                TdApi.ChatNotificationSettings old = current.notificationSettings;
                TdApi.ChatNotificationSettings settings = new TdApi.ChatNotificationSettings(
                        i == 4, durations[i], old.useDefaultSound, old.soundId,
                        old.useDefaultShowPreview, old.showPreview, old.useDefaultMuteStories, old.muteStories,
                        old.useDefaultStorySound, old.storySoundId, old.useDefaultShowStoryPoster, old.showStoryPoster,
                        old.useDefaultDisablePinnedMessageNotifications, old.disablePinnedMessageNotifications,
                        old.useDefaultDisableMentionNotifications, old.disableMentionNotifications);
                update(activity, chat.id, new TdApi.SetChatNotificationSettings(chat.id, settings), changed);
            }));
        }).show();
    }

    private static void autoDelete(Activity activity, TdApi.Chat chat, Runnable changed) {
        String[] options = {"Off", "1 day", "1 week", "1 month"};
        int[] times = {0, 86400, 7 * 86400, 30 * 86400};
        int selected = -1;
        for (int i = 0; i < times.length; i++) if (times[i] == chat.messageAutoDeleteTime) selected = i;
        new AlertDialog.Builder(activity).setTitle("Auto-delete new messages")
                .setSingleChoiceItems(options, selected, (dialog, i) -> {
                    dialog.dismiss();
                    ConversationActions.confirm(activity, "Set auto-delete to " + options[i] + "?", () ->
                            update(activity, chat.id, new TdApi.SetChatMessageAutoDeleteTime(chat.id, times[i]), changed));
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void leave(Activity activity, TdApi.Chat chat, Runnable changed) {
        ConversationActions.confirm(activity, "Leave " + chat.title + "?", () ->
                update(activity, chat.id, new TdApi.LeaveChat(chat.id), changed));
    }

    private static void update(Activity activity, long chatId, TdApi.Function<?> query, Runnable changed) {
        if (!eligible(activity, chatId)) return;
        Td.send(query, result -> activity.runOnUiThread(() -> {
            if (!ConversationActions.active(activity)) return;
            if (result instanceof TdApi.Error) ConversationActions.toast(activity, ((TdApi.Error) result).message);
            else changed.run();
        }));
    }

    private static boolean eligible(Activity activity, long chatId) {
        return ConversationActions.active(activity) && Td.authorized() && Td.scope(chatId) != ChatScope.EXCLUDED;
    }

    static void joinGroup(Activity activity) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setSingleLine();
        field.setHint("https://t.me/+invite or @group");
        new AlertDialog.Builder(activity).setTitle("Join group").setView(field)
                .setPositiveButton("Look up", (d, w) -> lookup(activity, field.getText().toString().trim()))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void lookup(Activity activity, String value) {
        if (!Td.authorized() || !ConversationActions.active(activity)) return;
        Uri uri = Uri.parse(value);
        String path = uri.getPath();
        if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && ("t.me".equalsIgnoreCase(uri.getHost()) || "telegram.me".equalsIgnoreCase(uri.getHost()))
                && path != null && (path.startsWith("/+") || path.startsWith("/joinchat/"))) {
            Td.send(new TdApi.CheckChatInviteLink(value), result -> activity.runOnUiThread(() -> {
                if (!ConversationActions.active(activity)) return;
                if (result instanceof TdApi.ChatInviteLinkInfo) {
                    TdApi.ChatInviteLinkInfo info = (TdApi.ChatInviteLinkInfo) result;
                    if (!(info.type instanceof TdApi.InviteLinkChatTypeBasicGroup)
                            && !(info.type instanceof TdApi.InviteLinkChatTypeSupergroup)) {
                        ConversationActions.toast(activity, "This link does not point to a supported group.");
                        return;
                    }
                    if (info.chatId == 0) {
                        confirmInvite(activity, value, info);
                    } else {
                        Td.resolveChat(info.chatId, chat -> activity.runOnUiThread(() -> {
                            if (chat != null && eligible(activity, chat.id)) confirmInvite(activity, value, info);
                            else if (ConversationActions.active(activity)) ConversationActions.toast(activity, "Group unavailable.");
                        }));
                    }
                } else if (result instanceof TdApi.Error) {
                    ConversationActions.toast(activity, ((TdApi.Error) result).message);
                }
            }));
        } else {
            String username = value.startsWith("@") ? value.substring(1) : value;
            if (!username.matches("[a-zA-Z0-9_]{3,32}")) {
                ConversationActions.toast(activity, "Enter a Telegram group invite link or public username.");
                return;
            }
            Td.send(new TdApi.SearchPublicChat(username), result -> {
                if (result instanceof TdApi.Chat) {
                    long id = ((TdApi.Chat) result).id;
                    Td.resolveChat(id, chat -> activity.runOnUiThread(() -> {
                        if (!ConversationActions.active(activity)) return;
                        if (chat != null && chat.type instanceof TdApi.ChatTypeSupergroup && eligible(activity, id)) {
                            join(activity, chat);
                        } else ConversationActions.toast(activity, "This username does not point to a supported group.");
                    }));
                } else if (result instanceof TdApi.Error) activity.runOnUiThread(() -> {
                    if (ConversationActions.active(activity)) ConversationActions.toast(activity, ((TdApi.Error) result).message);
                });
            });
        }
    }

    private static void confirmInvite(Activity activity, String link, TdApi.ChatInviteLinkInfo info) {
        ConversationActions.confirm(activity, "Join " + info.title + "?", () -> {
            if (Td.authorized() && (info.chatId == 0 || eligible(activity, info.chatId))) {
                joinResult(activity, new TdApi.JoinChatByInviteLink(link));
            }
        });
    }

    private static void join(Activity activity, TdApi.Chat chat) {
        ConversationActions.confirm(activity, "Join " + chat.title + "?", () -> {
            if (eligible(activity, chat.id)) joinResult(activity, new TdApi.JoinChat(chat.id));
        });
    }

    private static void joinResult(Activity activity, TdApi.Function<TdApi.ChatJoinResult> query) {
        Td.send(query, result -> {
            if (result instanceof TdApi.ChatJoinResultSuccess) {
                long id = ((TdApi.ChatJoinResultSuccess) result).chatId;
                Td.resolveChat(id, chat -> activity.runOnUiThread(() -> {
                    if (chat != null && eligible(activity, id)) {
                        activity.startActivity(new Intent(activity, ChatActivity.class)
                                .putExtra(ChatActivity.EXTRA_CHAT_ID, id));
                    }
                }));
            } else activity.runOnUiThread(() -> {
                if (!ConversationActions.active(activity)) return;
                String message = result instanceof TdApi.Error ? ((TdApi.Error) result).message
                        : result instanceof TdApi.ChatJoinResultRequestSent ? "Join request sent."
                        : result instanceof TdApi.ChatJoinResultGuardBotApprovalRequired
                            ? "The group requires approval through its guard bot." : "Join request declined.";
                ConversationActions.toast(activity, message);
            });
        });
    }
}
