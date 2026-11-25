package ai.crystalgram.holo;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;

final class MessageWidgets {
    private MessageWidgets() {}

    static void bind(ChatActivity activity, LinearLayout panel, TdApi.Message message) {
        panel.removeAllViews();
        if (message.content instanceof TdApi.MessagePoll) {
            TdApi.Poll poll = ((TdApi.MessagePoll) message.content).poll;
            if (poll != null && !poll.isClosed && poll.voteRestrictionReason == null && poll.options.length > 0) {
                boolean voted = false;
                for (TdApi.PollOption option : poll.options) voted |= option.isChosen;
                if (!voted || poll.allowsRevoting) button(activity, panel, "Vote", () -> vote(activity, message, poll));
            }
        }
        if (!(message.replyMarkup instanceof TdApi.ReplyMarkupInlineKeyboard)) return;
        for (TdApi.InlineKeyboardButton[] buttons : ((TdApi.ReplyMarkupInlineKeyboard) message.replyMarkup).rows) {
            LinearLayout row = new LinearLayout(activity);
            for (TdApi.InlineKeyboardButton button : buttons) {
                Button view = new Button(activity);
                view.setText(button.text);
                view.setEnabled(button.type instanceof TdApi.InlineKeyboardButtonTypeCallback
                        || (button.type instanceof TdApi.InlineKeyboardButtonTypeUrl
                            && webUrl(((TdApi.InlineKeyboardButtonTypeUrl) button.type).url) != null));
                view.setOnClickListener(v -> click(activity, message, button));
                row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
            }
            panel.addView(row);
        }
    }

    private static void button(ChatActivity activity, LinearLayout panel, String label, Runnable action) {
        Button view = new Button(activity);
        view.setText(label);
        view.setOnClickListener(v -> action.run());
        panel.addView(view);
    }

    private static void click(ChatActivity activity, TdApi.Message message, TdApi.InlineKeyboardButton button) {
        if (!activity.canUse(message)) return;
        if (button.type instanceof TdApi.InlineKeyboardButtonTypeUrl) {
            openWeb(activity, ((TdApi.InlineKeyboardButtonTypeUrl) button.type).url);
        } else if (button.type instanceof TdApi.InlineKeyboardButtonTypeCallback) {
            byte[] data = ((TdApi.InlineKeyboardButtonTypeCallback) button.type).data;
            Td.send(new TdApi.GetCallbackQueryAnswer(message.chatId, message.id,
                    new TdApi.CallbackQueryPayloadData(data)), result -> activity.runOnUiThread(() -> {
                if (!activity.canUse(message)) return;
                if (result instanceof TdApi.Error) {
                    ConversationActions.toast(activity, ((TdApi.Error) result).message);
                } else if (result instanceof TdApi.CallbackQueryAnswer) {
                    TdApi.CallbackQueryAnswer answer = (TdApi.CallbackQueryAnswer) result;
                    if (answer.text != null && answer.text.length() > 0) {
                        if (answer.showAlert) new AlertDialog.Builder(activity).setMessage(answer.text)
                                .setPositiveButton(android.R.string.ok, null).show();
                        else ConversationActions.toast(activity, answer.text);
                    }
                    if (webUrl(answer.url) != null) openWeb(activity, answer.url);
                }
            }));
        }
    }

    private static Uri webUrl(String value) {
        if (value == null) return null;
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (host == null || (!"https".equalsIgnoreCase(uri.getScheme())
                && !"http".equalsIgnoreCase(uri.getScheme()))) return null;
        host = host.toLowerCase(java.util.Locale.US);
        if (host.equals("t.me") || host.endsWith(".t.me") || host.equals("telegram.me")
                || host.endsWith(".telegram.me") || host.equals("telegram.dog")) return null;
        return uri;
    }

    private static void openWeb(ChatActivity activity, String url) {
        Uri uri = webUrl(url);
        if (uri == null) return;
        new AlertDialog.Builder(activity).setTitle("Open website?").setMessage(uri.toString())
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE));
                    } catch (android.content.ActivityNotFoundException e) {
                        ConversationActions.toast(activity, "No browser is available.");
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    static String pollText(TdApi.Poll poll) {
        if (poll == null) return "Poll";
        StringBuilder text = new StringBuilder(poll.question.text);
        for (int i : optionOrder(poll)) {
            TdApi.PollOption option = poll.options[i];
            text.append('\n').append(option.isChosen ? "✓ " : "• ").append(option.text.text);
            if (poll.canSeeResults) text.append("  ").append(option.votePercentage).append('%');
        }
        if (poll.isClosed) text.append("\nPoll closed");
        if (poll.canSeeResults) text.append("\n").append(poll.totalVoterCount).append(" votes");
        return text.toString();
    }

    private static int[] optionOrder(TdApi.Poll poll) {
        int n = poll.options.length;
        int[] order = poll.optionOrder;
        if (order != null && order.length == n) {
            boolean[] seen = new boolean[n];
            boolean valid = true;
            for (int index : order) {
                if (index < 0 || index >= n || seen[index]) {
                    valid = false;
                    break;
                }
                seen[index] = true;
            }
            if (valid) return order;
        }
        int[] natural = new int[n];
        for (int i = 0; i < n; i++) natural[i] = i;
        return natural;
    }

    private static void vote(ChatActivity activity, TdApi.Message message, TdApi.Poll poll) {
        if (!activity.canUse(message)) return;
        int[] order = optionOrder(poll);
        String[] labels = new String[order.length];
        boolean[] selected = new boolean[order.length];
        for (int i = 0; i < order.length; i++) {
            labels[i] = poll.options[order[i]].text.text;
            selected[i] = poll.options[order[i]].isChosen;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle(poll.question.text)
                .setNegativeButton(android.R.string.cancel, null);
        if (poll.allowsMultipleAnswers) {
            dialog.setMultiChoiceItems(labels, selected, (d, index, checked) -> selected[index] = checked)
                    .setPositiveButton("Submit vote", (d, w) -> {
                        ArrayList<Integer> chosen = new ArrayList<>();
                        for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(order[i]);
                        int[] ids = new int[chosen.size()];
                        for (int i = 0; i < ids.length; i++) ids[i] = chosen.get(i);
                        submit(activity, message, ids);
                    });
        } else {
            dialog.setItems(labels, (d, index) -> submit(activity, message, new int[] {order[index]}));
            if (poll.allowsRevoting && !(poll.type instanceof TdApi.PollTypeQuiz)) {
                for (boolean voted : selected) {
                    if (voted) {
                        dialog.setNeutralButton("Retract vote", (d, w) -> submit(activity, message, new int[0]));
                        break;
                    }
                }
            }
        }
        dialog.show();
    }

    private static void submit(ChatActivity activity, TdApi.Message message, int[] ids) {
        if (activity.canUse(message)) {
            ConversationActions.request(activity, new TdApi.SetPollAnswer(message.chatId, message.id, ids));
        }
    }
}
