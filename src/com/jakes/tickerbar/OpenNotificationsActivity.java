package com.jakes.tickerbar;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** No-UI shortcut target: bind a Nova gesture to this. */
public class OpenNotificationsActivity extends Activity {
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        if (!ShadeService.notifications()) {
            Toast.makeText(this, "Enable TickerBar in Accessibility first",
                    Toast.LENGTH_LONG).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
