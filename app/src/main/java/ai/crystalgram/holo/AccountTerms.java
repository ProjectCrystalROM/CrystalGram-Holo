package ai.crystalgram.holo;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;

import org.drinkless.tdlib.TdApi;

/** Keeps a received terms notice in force until Telegram acknowledges a decision. */
final class AccountTerms implements Application.ActivityLifecycleCallbacks {
    static final class Notice {
        final String id;
        final String text;
        final int minAge;

        Notice(String id, String text, int minAge) {
            this.id = id;
            this.text = text;
            this.minAge = minAge;
        }
    }

    private final SharedPreferences storage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Notice pending;
    private Activity foreground;
    private boolean submitting;

    AccountTerms(Application application) {
        storage = application.getSharedPreferences("telegram_terms", 0);
        String id = storage.getString("id", "");
        if (!id.isEmpty()) {
            pending = new Notice(id, storage.getString("text", ""), storage.getInt("minAge", 0));
        }
        application.registerActivityLifecycleCallbacks(this);
    }

    Notice pending() { return pending; }

    static boolean allowsWhilePending(TdApi.Function<?> query) {
        if (query instanceof TdApi.SetOption) {
            TdApi.SetOption option = (TdApi.SetOption) query;
            return "online".equals(option.name) && option.value instanceof TdApi.OptionValueBoolean
                    && !((TdApi.OptionValueBoolean) option.value).value;
        }
        if (query instanceof TdApi.SendChatAction) {
            TdApi.ChatAction action = ((TdApi.SendChatAction) query).action;
            return action == null || action instanceof TdApi.ChatActionCancel;
        }
        return query instanceof TdApi.AcceptTermsOfService || query instanceof TdApi.DeleteAccount
                || query instanceof TdApi.SetTdlibParameters || query instanceof TdApi.SetNetworkType
                || query instanceof TdApi.GetMe || query instanceof TdApi.CloseChat;
    }

    void update(TdApi.UpdateTermsOfService update) {
        TdApi.TermsOfService terms = update.termsOfService;
        if (update.termsOfServiceId == null || update.termsOfServiceId.isEmpty() || terms == null) return;
        String text = terms.text == null || terms.text.text == null ? "" : terms.text.text;
        pending = new Notice(update.termsOfServiceId, text, terms.minUserAge);
        storage.edit().putString("id", pending.id).putString("text", pending.text)
                .putInt("minAge", pending.minAge).apply();
        Td.setOnline(false);
        Td.onTermsGateChanged();
        showGate(foreground);
    }

    void clear() {
        if (pending == null) return;
        pending = null;
        storage.edit().clear().apply();
        Td.onTermsGateChanged();
    }

    @Override public void onActivityResumed(Activity activity) {
        foreground = activity;
        if (pending != null && !(activity instanceof LoginActivity)) {
            main.post(() -> showGate(activity));
        }
    }

    @Override public void onActivityPaused(Activity activity) {
        if (foreground == activity) foreground = null;
    }

    private void showGate(Activity activity) {
        if (pending == null || activity == null || activity != foreground || activity.isFinishing()) return;
        if (activity instanceof LoginActivity) {
            ((LoginActivity) activity).showPendingTerms();
        } else {
            activity.startActivity(new Intent(activity, LoginActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }
    }

    AlertDialog createDialog(LoginActivity activity, Notice notice) {
        String message = "Review the updated terms in Telegram on a modern device, or at telegram.org/tos. "
                + "CrystalGram stays locked until you make a decision.\n\n" + notice.text;
        if (notice.minAge > 0) message += "\n\nBy accepting, you confirm that you are at least " + notice.minAge + ".";
        return new AlertDialog.Builder(activity).setTitle("Updated Telegram terms")
                .setMessage(message).setCancelable(false)
                .setPositiveButton("Accept", (d, w) -> submit(activity, notice, null))
                .setNegativeButton("Decline", (d, w) -> confirmDecline(activity, notice))
                .setNeutralButton("Review later", (d, w) -> activity.finish()).create();
    }

    private void confirmDecline(LoginActivity activity, Notice notice) {
        EditText password = new EditText(activity);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setHint("Two-step verification password, if enabled");
        new AlertDialog.Builder(activity).setTitle("Permanently delete your Telegram account?")
                .setMessage("Telegram requires account deletion to formally decline updated terms. "
                        + "This removes your account and its data from Telegram. "
                        + "Choose Cancel to keep the account and leave CrystalGram blocked.")
                .setView(password)
                .setPositiveButton("Delete account", (d, w) -> submit(activity, notice, password.getText().toString()))
                .setNegativeButton(android.R.string.cancel, (d, w) -> activity.showPendingTerms())
                .setOnCancelListener(d -> activity.showPendingTerms()).show();
    }

    private void submit(LoginActivity activity, Notice notice, String password) {
        if (pending == null || !pending.id.equals(notice.id) || !Td.sessionReady() || submitting) {
            activity.showPendingTerms();
            return;
        }
        submitting = true;
        boolean accepting = password == null;
        TdApi.Function<TdApi.Ok> request = accepting ? new TdApi.AcceptTermsOfService(notice.id)
                : new TdApi.DeleteAccount("Decline ToS update", password);
        Td.send(request, result -> main.post(() -> {
            submitting = false;
            if (accepting && result instanceof TdApi.Ok && pending != null && pending.id.equals(notice.id)) clear();
            if (foreground instanceof LoginActivity) {
                LoginActivity login = (LoginActivity) foreground;
                if (result instanceof TdApi.Error) {
                    ConversationActions.toast(login, ((TdApi.Error) result).message);
                }
                if (!accepting && result instanceof TdApi.Ok) {
                    login.waitForAccountDeletion();
                } else if (pending != null) {
                    login.showPendingTerms();
                } else {
                    login.resumeAfterTerms();
                }
            }
        }));
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
