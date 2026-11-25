package ai.crystalgram.holo;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

/** Build/diagnostic info. Framework-only. */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView tv = new TextView(this);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        tv.setTextSize(14);
        tv.setText(
            getString(R.string.app_name) + "\n" +
            getString(R.string.release_branding, BuildConfig.VERSION_NAME) + "\n" +
            getString(R.string.release_subname) + "\n" +
            getString(R.string.release_tagline) + "\n\n" +
            "Device      : " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Android     : " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "ABI         : " + Build.CPU_ABI
        );

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
