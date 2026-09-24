package com.jakes.tickerbar;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Shows over the lock screen just long enough to ask for an unlock. It opens nothing
 * itself: the service queued the target and opens it once the phone is unlocked, so there
 * is exactly one path to it. Not exported, so other apps can't drive it.
 */
public class WalletLaunchActivity extends Activity {

    private boolean asked;
    private int tries;
    private long askedAt;

    /** Asking before the window has focus gets the request rejected by the system. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !asked) { asked = true; requestUnlock(); }
    }

    @Override protected void onResume() {
        super.onResume();
        // fallback in case focus never arrives (it can stay with the keyguard)
        getWindow().getDecorView().postDelayed(new Runnable() { public void run() {
            if (!asked) { asked = true; requestUnlock(); }
        }}, 600);
    }

    private void requestUnlock() {
        final KeyguardManager km = getSystemService(KeyguardManager.class);
        Log.d("TickerBar", "trampoline asking (try " + (tries + 1) + "), locked="
                + (km != null && km.isKeyguardLocked()));
        if (km == null || !km.isKeyguardLocked()) { finish(); return; }
        askedAt = SystemClock.uptimeMillis();
        km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { Log.d("TickerBar", "dismiss SUCCEEDED"); finish(); }
            @Override public void onDismissError() { Log.d("TickerBar", "dismiss ERROR"); finish(); }
            @Override public void onDismissCancelled() {
                long after = SystemClock.uptimeMillis() - askedAt;
                // A cancel within a fraction of a second is the system rejecting the request,
                // not the user backing out of the prompt (that takes seconds). Ask again.
                if (after < 800 && tries < 2 && km.isKeyguardLocked()) {
                    tries++;
                    Log.d("TickerBar", "dismiss rejected after " + after + "ms; retrying");
                    getWindow().getDecorView().postDelayed(new Runnable() {
                        public void run() { requestUnlock(); }
                    }, 300);
                } else {
                    Log.d("TickerBar", "dismiss CANCELLED after " + after + "ms");
                    finish();
                }
            }
        });
    }
}
