package ai.crystalgram.holo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/**
 * Loads the TDLib JNI bridge and reports the outcome on screen.
 *
 * This exists to prove one thing before any UI is built on top of it: that
 * libtdjni.so and its two OpenSSL companions actually load on API 18 ARMv7.
 * If the static initialiser throws, the failure text is shown instead of a
 * stack trace buried in logcat.
 */
public class TdProbeActivity extends Activity {

    private static final String TAG = "CG-TdProbe";

    private static String loadResult;

    static {
        try {
            System.loadLibrary("tdjni");
            loadResult = "libtdjni.so loaded OK";
            Log.i(TAG, loadResult);
        } catch (Throwable t) {
            loadResult = "LOAD FAILED: " + t.getClass().getName() + ": " + t.getMessage();
            Log.e(TAG, loadResult, t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String detail = loadResult;

        // If the library loaded, ask TDLib for its version. That proves the JNI
        // symbols resolve and the native code runs, not just that the file mapped.
        if (detail.startsWith("libtdjni.so loaded OK")) {
            try {
                detail += "\n\nTDLib version: " + callGetVersion();
            } catch (Throwable t) {
                detail += "\n\nJNI call failed: " + t.getClass().getName() + ": " + t.getMessage();
                Log.e(TAG, "JNI call failed", t);
            }
        }

        TextView tv = new TextView(this);
        tv.setText(detail);
        tv.setTextSize(14);
        tv.setPadding(24, 24, 24, 24);
        setContentView(tv);
    }

    /** Executes getOption("version") synchronously through Client.execute. */
    private String callGetVersion() throws Exception {
        TdApi.OptionValue v = Client.execute(new TdApi.GetOption("version"));
        if (v instanceof TdApi.OptionValueString) {
            return ((TdApi.OptionValueString) v).value;
        }
        return "(unexpected: " + (v == null ? "null" : v.getClass().getSimpleName()) + ")";
    }
}
