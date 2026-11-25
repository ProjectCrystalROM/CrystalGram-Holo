package ai.crystalgram.holo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/**
 * First-run screen: disclaimer, then the Telegram login flow driven by TDLib's
 * authorizationStateUpdated.
 *
 * ToS compliance: the disclosure that this app uses the Telegram API and is part
 * of the Telegram ecosystem is shown here, before any credentials are asked for.
 */
public class LoginActivity extends Activity {

    private static final String TAG = "CG-Login";

    private TextView status;
    private EditText field;
    private Button action;
    private ProgressBar spinner;

    private boolean started;
    private boolean destroyed;
    static final String NOTIFICATION_ENTRY = "ai.crystalgram.holo.NotificationEntry";

    private boolean resumed;
    private android.app.AlertDialog termsDialog;
    private String termsDialogId;
    private TdApi.AuthorizationState state;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** What the current input field expects. */
    private enum Step { NONE, PHONE, CODE, PASSWORD, EMAIL, EMAIL_CODE }

    private Step step = Step.NONE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView disclosure = new TextView(this);
        disclosure.setText(
                "CrystalGram Holo is an unofficial client that uses the Telegram API "
              + "and is part of the Telegram ecosystem.\n\n"
              + "It is not affiliated with, endorsed by, or produced by Telegram.\n\n"
              + "Your messages are sent through Telegram's servers. TDLib (the Telegram "
              + "Database Library, licensed under the Boost Software License) handles "
              + "networking, encryption and local storage.");
        disclosure.setTextSize(13);
        root.addView(disclosure);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, pad, 0, dp(8));
        root.addView(status);

        field = new EditText(this);
        field.setSingleLine(true);
        field.setVisibility(View.GONE);
        root.addView(field);

        action = new Button(this);
        action.setVisibility(View.GONE);
        action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submit(); }
        });
        root.addView(action);

        spinner = new ProgressBar(this);
        spinner.setVisibility(View.GONE);
        root.addView(spinner);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        if (BuildConfig.TG_API_ID == 0 || BuildConfig.TG_API_HASH.length() == 0) {
            status.setText("Configure Telegram API credentials in local.properties and rebuild.");
            return;
        }
        status.setText("Connecting to Telegram\u2026");
        try {
            Td.ensureStarted(this);
            started = true;
        } catch (LinkageError | RuntimeException e) {
            Log.e(TAG, "TDLib startup failed", e);
            status.setText("TDLib failed to start: " + e.getMessage());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (started) Td.setLogin(this);
    }

    @Override protected void onPause() {
        resumed = false;
        Td.clearLogin(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        dismissTermsDialog();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void ask(Step next, String prompt, String hint, int inputType) {
        step = next;
        status.setText(prompt);
        field.setVisibility(View.VISIBLE);
        field.setHint(hint);
        field.setInputType(inputType);
        field.setText("");
        action.setVisibility(View.VISIBLE);
        action.setText("Continue");
        spinner.setVisibility(View.GONE);
        field.requestFocus();
    }

    private void busy(String text) {
        step = Step.NONE;
        status.setText(text);
        field.setVisibility(View.GONE);
        action.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
    }

    private void submit() {
        String value = field.getText().toString();
        if (step != Step.PASSWORD) value = value.trim();
        if (value.length() == 0) return;

        TdApi.Function<?> request;
        switch (step) {
            case PHONE: request = new TdApi.SetAuthenticationPhoneNumber(value, null); break;
            case CODE: request = new TdApi.CheckAuthenticationCode(value); break;
            case PASSWORD: request = new TdApi.CheckAuthenticationPassword(value); break;
            case EMAIL: request = new TdApi.SetAuthenticationEmailAddress(value); break;
            case EMAIL_CODE:
                request = new TdApi.CheckAuthenticationEmailCode(new TdApi.EmailAddressAuthenticationCode(value));
                break;
            default: return;
        }
        busy("Contacting Telegram\u2026");
        final TdApi.AuthorizationState submittedState = state;
        Td.send(request, new Client.ResultHandler() {
            @Override public void onResult(TdApi.Object result) {
                if (!(result instanceof TdApi.Error)) return;
                final String message = ((TdApi.Error) result).message;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (state != submittedState || isFinishing() || destroyed) return;
                        onAuthorizationState(state);
                        showError(message);
                    }
                });
            }
        });
    }

    void showError(String message) {
        spinner.setVisibility(View.GONE);
        status.setText("Telegram said: " + message);
    }

    void onAuthorizationState(TdApi.AuthorizationState next) {
        state = next;
        if (!(next instanceof TdApi.AuthorizationStateReady)) dismissTermsDialog();
        if (next instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            busy("Setting up\u2026");
        } else if (next instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            ask(Step.PHONE, "Enter your phone number (international format):",
                    "+1 555 123 4567", InputType.TYPE_CLASS_PHONE);
        } else if (next instanceof TdApi.AuthorizationStateWaitCode) {
            ask(Step.CODE, "Enter the code Telegram sent you:", "Code", InputType.TYPE_CLASS_TEXT);
        } else if (next instanceof TdApi.AuthorizationStateWaitPassword) {
            ask(Step.PASSWORD, "Enter your two-step verification password:", "Password",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else if (next instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            ask(Step.EMAIL, "Enter your email address:", "Email address",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        } else if (next instanceof TdApi.AuthorizationStateWaitEmailCode) {
            ask(Step.EMAIL_CODE, "Enter the code Telegram emailed you:", "Code", InputType.TYPE_CLASS_TEXT);
        } else if (next instanceof TdApi.AuthorizationStateWaitRegistration) {
            ask(Step.PHONE, getString(R.string.login_existing_account_only),
                    "+1 555 123 4567", InputType.TYPE_CLASS_PHONE);
        } else if (next instanceof TdApi.AuthorizationStateReady) {
            if (!resumed || isFinishing()) return;
            if (Td.termsBlocked()) {
                showPendingTerms();
                return;
            }
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED
                    && !getPreferences(MODE_PRIVATE).getBoolean("askedNotifications", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotifications", true).apply();
                requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 1);
                return;
            }
            enterApp();
        } else if (next instanceof TdApi.AuthorizationStateLoggingOut
                || next instanceof TdApi.AuthorizationStateClosing
                || next instanceof TdApi.AuthorizationStateClosed) {
            busy("Closing the Telegram session\u2026");
        } else {
            busy("Waiting for Telegram\u2026");
            showError("This sign-in step requires another Telegram client.");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 1 && Td.authorized() && !isFinishing()) enterApp();
    }

    void showPendingTerms() {
        if (!resumed || isFinishing() || !Td.sessionReady() || !Td.termsBlocked()) return;
        AccountTerms gate = Td.accountTerms();
        AccountTerms.Notice notice = gate.pending();
        if (termsDialog != null && termsDialog.isShowing() && notice.id.equals(termsDialogId)) return;
        dismissTermsDialog();
        busy("Review Telegram’s updated terms to continue.");
        spinner.setVisibility(View.GONE);
        termsDialogId = notice.id;
        termsDialog = gate.createDialog(this, notice);
        termsDialog.show();
    }

    private void dismissTermsDialog() {
        if (termsDialog != null) {
            termsDialog.dismiss();
            termsDialog = null;
        }
    }

    void waitForAccountDeletion() {
        dismissTermsDialog();
        if (!isFinishing() && Td.sessionReady()) busy("Waiting for Telegram to close the account…");
    }

    void resumeAfterTerms() {
        dismissTermsDialog();
        if (resumed && !isFinishing() && Td.authorized()) onAuthorizationState(state);
    }

    private void enterApp() {
        if (!Td.authorized()) {
            showPendingTerms();
            return;
        }
        NotificationService.start(this);
        Intent destination = new Intent(this, MainActivity.class);
        if (getComponentName().getClassName().equals(NOTIFICATION_ENTRY)
                && getIntent().hasExtra(ChatActivity.EXTRA_CHAT_ID)) {
            destination.setClass(this, ChatActivity.class);
            destination.putExtras(getIntent());
        }
        startActivity(destination);
        finish();
    }
}
