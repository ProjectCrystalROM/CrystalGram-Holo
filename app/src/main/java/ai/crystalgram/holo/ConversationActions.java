package ai.crystalgram.holo;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class ConversationActions {
    private ConversationActions() {}

    static void request(Activity activity, TdApi.Function<?> query) {
        Td.send(query, result -> activity.runOnUiThread(() -> {
            if (result instanceof TdApi.Error && active(activity)) {
                toast(activity, ((TdApi.Error) result).message);
            }
        }));
    }

    static boolean active(Activity activity) {
        return !activity.isFinishing() && activity.getWindow().getDecorView().getWindowToken() != null;
    }

    static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    static void confirm(Activity activity, String title, Runnable action) {
        new AlertDialog.Builder(activity).setTitle(title)
                .setPositiveButton(android.R.string.ok, (d, w) -> action.run())
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    static TdApi.InputMessageText text(String value, TdApi.TextEntity[] entities) {
        TdApi.InputMessageText result = new TdApi.InputMessageText();
        result.text = new TdApi.FormattedText(value, entities);
        return result;
    }

    static void menu(ChatActivity activity, TdApi.Message message) {
        if (!activity.canUse(message)) return;
        Td.send(new TdApi.GetMessageProperties(message.chatId, message.id), result ->
                activity.runOnUiThread(() -> {
                    if (!activity.canUse(message)) return;
                    if (!(result instanceof TdApi.MessageProperties)) {
                        if (result instanceof TdApi.Error) toast(activity, ((TdApi.Error) result).message);
                        return;
                    }
                    TdApi.MessageProperties properties = (TdApi.MessageProperties) result;
                    Map<String, Runnable> choices = new LinkedHashMap<>();
                    if (properties.canBeReplied && activity.canCompose()) {
                        choices.put("Reply", () -> activity.replyTo(message));
                    }
                    if (properties.canBeEdited && message.content instanceof TdApi.MessageText) {
                        choices.put("Edit", () -> edit(activity, message));
                    }
                    if (properties.canBeForwarded && message.selfDestructType == null) {
                        choices.put("Forward", () -> forward(activity, message));
                    }
                    choices.put("React", () -> reactions(activity, message));
                    if (properties.canBeDeletedOnlyForSelf) {
                        choices.put("Delete for me", () -> delete(activity, message, false));
                    }
                    if (properties.canBeDeletedForAllUsers) {
                        choices.put("Delete for everyone", () -> delete(activity, message, true));
                    }
                    String[] labels = choices.keySet().toArray(new String[0]);
                    new AlertDialog.Builder(activity).setItems(labels, (d, index) -> {
                        if (activity.canUse(message)) choices.get(labels[index]).run();
                    }).show();
                }));
    }

    private static void edit(ChatActivity activity, TdApi.Message message) {
        TdApi.FormattedText original = ((TdApi.MessageText) message.content).text;
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setText(original.text);
        new AlertDialog.Builder(activity).setTitle("Edit message").setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    String value = field.getText().toString();
                    if (activity.canUse(message) && value.trim().length() > 0 && !value.equals(original.text)) {
                        request(activity, new TdApi.EditMessageText(message.chatId, message.id, message.replyMarkup,
                                text(value, null)));
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private static void delete(ChatActivity activity, TdApi.Message message, boolean everyone) {
        confirm(activity, everyone ? "Delete for everyone?" : "Delete for me?", () -> {
            if (activity.canUse(message)) {
                request(activity, new TdApi.DeleteMessages(message.chatId, new long[] {message.id}, everyone));
            }
        });
    }

    private static void forward(ChatActivity activity, TdApi.Message message) {
        ArrayList<TdApi.Chat> destinations = Td.forwardDestinations();
        if (destinations.isEmpty()) {
            toast(activity, "No eligible destination chats are loaded.");
            return;
        }
        String[] labels = new String[destinations.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = Td.chatTitle(destinations.get(i));
        new AlertDialog.Builder(activity).setTitle("Forward to").setItems(labels, (d, index) -> {
            long id = destinations.get(index).id;
            if (Td.scope(id) == ChatScope.FORUM) {
                activity.startActivity(new android.content.Intent(activity, ForumActivity.class)
                        .putExtra(ChatActivity.EXTRA_CHAT_ID, id)
                        .putExtra(ForumActivity.EXTRA_FORWARD_CHAT, message.chatId)
                        .putExtra(ForumActivity.EXTRA_FORWARD_MESSAGE, message.id));
            } else if (activity.canUse(message) && Td.scope(id) == ChatScope.CONVERSATION) {
                request(activity, new TdApi.ForwardMessages(id, null, message.chatId,
                        new long[] {message.id}, null, false, false));
            }
        }).show();
    }

    private static void reactions(ChatActivity activity, TdApi.Message message) {
        Td.send(new TdApi.GetMessageAvailableReactions(message.chatId, message.id, 6), result ->
                activity.runOnUiThread(() -> {
                    if (!activity.canUse(message)) return;
                    if (result instanceof TdApi.Error) {
                        toast(activity, ((TdApi.Error) result).message);
                        return;
                    }
                    if (!(result instanceof TdApi.AvailableReactions)) return;
                    TdApi.AvailableReactions available = (TdApi.AvailableReactions) result;
                    Map<String, TdApi.ReactionType> choices = new LinkedHashMap<>();
                    addReactions(choices, available.topReactions);
                    addReactions(choices, available.recentReactions);
                    addReactions(choices, available.popularReactions);
                    if (choices.isEmpty()) {
                        toast(activity, "No standard emoji reactions are available.");
                        return;
                    }
                    String[] emojis = choices.keySet().toArray(new String[0]);
                    new AlertDialog.Builder(activity).setTitle("React").setItems(emojis, (d, index) -> {
                        if (!activity.canUse(message)) return;
                        String emoji = emojis[index];
                        TdApi.ReactionType type = choices.get(emoji);
                        request(activity, chosen(message, emoji)
                                ? new TdApi.RemoveMessageReaction(message.chatId, message.id, type)
                                : new TdApi.AddMessageReaction(message.chatId, message.id, type, false, true));
                    }).show();
                }));
    }

    private static void addReactions(Map<String, TdApi.ReactionType> choices, TdApi.AvailableReaction[] reactions) {
        if (reactions == null) return;
        for (TdApi.AvailableReaction reaction : reactions) {
            if (reaction.type instanceof TdApi.ReactionTypeEmoji && !reaction.needsPremium) {
                choices.put(((TdApi.ReactionTypeEmoji) reaction.type).emoji, reaction.type);
            }
        }
    }

    private static boolean chosen(TdApi.Message message, String emoji) {
        if (message.interactionInfo == null || message.interactionInfo.reactions == null) return false;
        for (TdApi.MessageReaction reaction : message.interactionInfo.reactions.reactions) {
            if (reaction.type instanceof TdApi.ReactionTypeEmoji && reaction.isChosen
                    && emoji.equals(((TdApi.ReactionTypeEmoji) reaction.type).emoji)) return true;
        }
        return false;
    }

    static String reactionsText(TdApi.Message message) {
        if (message.interactionInfo == null || message.interactionInfo.reactions == null) return "";
        StringBuilder text = new StringBuilder();
        for (TdApi.MessageReaction reaction : message.interactionInfo.reactions.reactions) {
            if (reaction.type instanceof TdApi.ReactionTypeEmoji) {
                if (text.length() != 0) text.append("  ");
                text.append(((TdApi.ReactionTypeEmoji) reaction.type).emoji).append(' ').append(reaction.totalCount);
            }
        }
        return text.toString();
    }
}
