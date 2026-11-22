package ai.crystalgram.holo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import org.drinkless.tdlib.TdApi;

/**
 * Keeps TDLib's idea of the network in step with the device's.
 *
 * TDLib does not poll the operating system for connectivity. It has a
 * setNetworkType request precisely because the client is expected to tell it,
 * and the documented behaviour is blunt:
 *
 *   "Calling this method forces all network connections to reopen, mitigating
 *    the delay in switching between different networks, so it must be called
 *    whenever the network is changed, even if the network type remains the same."
 *
 * That "even if the network type remains the same" is the part that explains the
 * bug this class fixes. When a phone wakes from sleep, or a Wi-Fi connection
 * drops and comes back on the same SSID, the network type is unchanged but the
 * sockets TDLib holds are dead. Without a setNetworkType call TDLib can sit on
 * those dead sockets, and the only thing that clears them is restarting the
 * process - which is exactly what a force-stop does.
 *
 * It also reports the state to a listener so a screen can say something honest
 * instead of showing an empty chat list with no explanation.
 */
final class NetworkMonitor {

    private static final String TAG = "CG-Net";

    /** Reports a connectivity change worth re-sending to TDLib. */
    interface Listener {
        void onNetworkAvailable(boolean available, TdApi.NetworkType type);
    }

    private final Context context;
    private final ConnectivityManager cm;
    private BroadcastReceiver receiver;
    private Listener listener;

    NetworkMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.cm = (ConnectivityManager) this.context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
    }

    void start(Listener l) {
        listener = l;
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                sync("broadcast");
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        context.registerReceiver(receiver, f);
        sync("start");
    }

    void stop() {
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
                // already gone
            }
            receiver = null;
        }
    }

    /**
     * Reads the current network and tells TDLib. Safe to call at any time; the
     * documented semantics make a redundant call harmless.
     */
    void sync(String why) {
        TdApi.NetworkType type = currentType();
        Log.i(TAG, "sync(" + why + ") -> "
                + (type == null ? "null" : type.getClass().getSimpleName()));
        // null means networkTypeOther to TDLib, which is the right answer when
        // we cannot tell what we are on.
        Td.send(new TdApi.SetNetworkType(type));
        if (listener != null) {
            listener.onNetworkAvailable(!(type instanceof TdApi.NetworkTypeNone), type);
        }
    }

    private TdApi.NetworkType currentType() {
        try {
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return new TdApi.NetworkTypeNone();

            int t = ni.getType();
            if (t == ConnectivityManager.TYPE_WIFI
                    || t == ConnectivityManager.TYPE_WIMAX
                    || t == ConnectivityManager.TYPE_ETHERNET) {
                return new TdApi.NetworkTypeWiFi();
            }
            if (t == ConnectivityManager.TYPE_MOBILE) {
                return ni.isRoaming()
                        ? new TdApi.NetworkTypeMobileRoaming()
                        : new TdApi.NetworkTypeMobile();
            }
            return new TdApi.NetworkTypeOther();
        } catch (Throwable t) {
            // getActiveNetworkInfo throws on some builds when the service is gone
            Log.w(TAG, "network type query failed", t);
            return new TdApi.NetworkTypeOther();
        }
    }
}
