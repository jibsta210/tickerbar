package com.jakes.tickerbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the coordinator after boot/update. Kernel attempts are always user initiated. */
public class RootBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Shortcuts.publish(context);
        RootBootstrap.start(context);
    }
}
