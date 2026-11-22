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
            "CrystalGram Holo\n" +
            "skeleton build\n\n" +
            "Device      : " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
            "Board       : " + Build.BOARD + "\n" +
            "Android     : " + Build.VERSION.RELEASE
                              + "  (API " + Build.VERSION.SDK_INT + ")\n" +
            "ABI         : " + Build.CPU_ABI + "\n" +
            "Build       : " + Build.DISPLAY + "\n\n" +
            "minSdkVersion : 16\n" +
            "UI toolkit    : framework only (Theme.Holo)\n" +
            "Backend       : TDLib 1.8.67, not yet linked\n\n" +
            "Chats shown in this build are fake data, used to\n" +
            "evaluate the UI on real hardware before TDLib\n" +
            "is wired up."
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
