package com.jakes.tickerbar;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** No-UI target for a launcher gesture. Honours the "home screen only" setting. */
public class OpenControlCentreActivity extends Activity {
    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        boolean allowed = !Prefs.on(this, Prefs.SHADE_HOME) || ShadeService.isOnHome();
        if (allowed && !ShadeService.quickSettings()) {
            Toast.makeText(this, "Enable TickerBar in Accessibility first", Toast.LENGTH_LONG).show();
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
