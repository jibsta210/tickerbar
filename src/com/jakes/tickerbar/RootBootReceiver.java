package com.jakes.tickerbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the coordinator after updates and arms one automatic attempt after boot. */
public class RootBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            RootBootstrap.request(context);
        } else {
            RootBootstrap.start(context);
        }
    }
}
