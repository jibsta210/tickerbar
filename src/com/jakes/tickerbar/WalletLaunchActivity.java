package com.jakes.tickerbar;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Shows over the lock screen just long enough to ask for an unlock. It opens nothing
 * itself: the service queued the launch and fires it once the phone is unlocked, so there
 * is exactly one path to the target. Not exported, so other apps can't drive it.
 */
public class WalletLaunchActivity extends Activity {

    private boolean asked;

    @Override protected void onResume() {
        super.onResume();
        if (asked) return;
        asked = true;
        // Ask only once the window is actually on screen: a request made from onCreate,
        // before the activity is visible, gets cancelled by the system.
        getWindow().getDecorView().post(new Runnable() { public void run() { requestUnlock(); } });
    }

    private void requestUnlock() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        Log.d("TickerBar", "trampoline asking, locked=" + (km != null && km.isKeyguardLocked()));
        if (km == null || !km.isKeyguardLocked()) { finish(); return; }
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { Log.d("TickerBar", "dismiss SUCCEEDED"); finish(); }
            @Override public void onDismissCancelled() { Log.d("TickerBar", "dismiss CANCELLED"); finish(); }
            @Override public void onDismissError() { Log.d("TickerBar", "dismiss ERROR"); finish(); }
        });
    }
}
