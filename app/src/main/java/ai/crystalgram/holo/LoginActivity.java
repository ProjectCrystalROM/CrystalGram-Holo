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

    private Client client;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** What the current input field expects. */
    private enum Step { NONE, PHONE, CODE, PASSWORD }

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

        setContentView(root);

        if (BuildConfig.TG_API_ID == 0 || BuildConfig.TG_API_HASH.length() == 0) {
            setStatus("No API credentials in local.properties.\n\n"
                    + "Add api_id and api_hash there, then rebuild. "
                    + "Auth cannot proceed without them.");
            return;
        }

        setStatus("Connecting to Telegram\u2026");
        // Register before starting TDLib: the update thread fires
        // WaitTdlibParameters immediately, and a listener attached afterwards
        // misses it -- which strands the client before SetTdlibParameters.
        Td.ensureStarted();
        Td.setLogin(this);
        startTdlib();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void setStatus(final String text) {
        ui.post(new Runnable() {
            @Override public void run() { status.setText(text); }
        });
    }

    /** Shows an input field plus button for one authorization step. */
    private void ask(final Step s, final String prompt, final String hint, final int inputType) {
        ui.post(new Runnable() {
            @Override public void run() {
                step = s;
                setStatus(prompt);
                field.setVisibility(View.VISIBLE);
                field.setHint(hint);
                field.setInputType(inputType);
                field.setText("");
                action.setVisibility(View.VISIBLE);
                action.setText("Continue");
                spinner.setVisibility(View.GONE);
                field.requestFocus();
            }
        });
    }

    private void busy(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                step = Step.NONE;
                setStatus(text);
                field.setVisibility(View.GONE);
                action.setVisibility(View.GONE);
                spinner.setVisibility(View.VISIBLE);
            }
        });
    }

    private void submit() {
        final String value = field.getText().toString().trim();
        if (value.length() == 0) return;

        switch (step) {
            case PHONE:
                busy("Sending code\u2026");
                send(new TdApi.SetAuthenticationPhoneNumber(value, null));
                break;
            case CODE:
                busy("Checking code\u2026");
                send(new TdApi.CheckAuthenticationCode(value));
                break;
            case PASSWORD:
                busy("Checking password\u2026");
                send(new TdApi.CheckAuthenticationPassword(value));
                break;
            default:
                break;
        }
    }

    private void send(TdApi.Function<?> query) {
        if (Td.client() == null) return;
        Td.send(query, new Client.ResultHandler() {
            @Override public void onResult(TdApi.Object object) {
                if (object instanceof TdApi.Error) {
                    final TdApi.Error e = (TdApi.Error) object;
                    Log.w(TAG, "error " + e.code + ": " + e.message);
                    setStatus("Telegram said: " + e.message);
                }
            }
        });
    }

    private void startTdlib() {
        try {
            // One client for the whole process: Td owns it so the chat list and
            // conversations keep working after login hands over.
            client = Td.client();
            send(new TdApi.GetOption("version"));
        } catch (Throwable t) {
            // TDLib aborts on a bindings/binary mismatch, so this rarely catches
            // anything -- but a failed load does surface here.
            Log.e(TAG, "TDLib failed to start", t);
            setStatus("TDLib failed to start: " + t.getMessage());
        }
    }

    /** Called by Td from the TDLib update thread. */
    void onAuthorizationState(TdApi.AuthorizationState state) {
        {
            Log.i(TAG, "auth state: " + state.getClass().getSimpleName());
            if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
                TdApi.SetTdlibParameters params = new TdApi.SetTdlibParameters();
                params.databaseDirectory = getDir("tdlib", MODE_PRIVATE).getAbsolutePath();
                params.useMessageDatabase = true;
                params.useSecretChats = false;
                params.apiId = BuildConfig.TG_API_ID;
                params.apiHash = BuildConfig.TG_API_HASH;
                params.systemLanguageCode = "en";
                params.deviceModel = android.os.Build.MODEL;
                params.systemVersion = android.os.Build.VERSION.RELEASE;
                params.applicationVersion = BuildConfig.VERSION_NAME;
                busy("Setting up\u2026");
                send(params);

            } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
                ask(Step.PHONE, "Enter your phone number (international format):",
                        "+1 555 123 4567", InputType.TYPE_CLASS_PHONE);

            } else if (state instanceof TdApi.AuthorizationStateWaitCode) {
                ask(Step.CODE, "Enter the code Telegram sent you:",
                        "12345", InputType.TYPE_CLASS_NUMBER);

            } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
                ask(Step.PASSWORD, "Enter your two-step verification password:",
                        "password",
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

            } else if (state instanceof TdApi.AuthorizationStateReady) {
                Log.i(TAG, "authorized");
                // Learn our own user id so typing indicators can ignore us.
                Td.send(new TdApi.GetMe(), new Client.ResultHandler() {
                    @Override public void onResult(TdApi.Object o) {
                        if (o instanceof TdApi.User) Td.setMe(((TdApi.User) o).id);
                    }
                });
                Td.clearLogin(this);
                ui.post(new Runnable() {
                    @Override public void run() {
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    }
                });

            } else if (state instanceof TdApi.AuthorizationStateClosing
                    || state instanceof TdApi.AuthorizationStateClosed) {
                Log.i(TAG, "TDLib closed");
            }
        }
    }
}
