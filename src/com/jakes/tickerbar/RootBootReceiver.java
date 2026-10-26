package com.jakes.tickerbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the coordinator after boot or an in-place Tickerbar update. */
public class RootBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        RootBootstrap.start(context);
    }
}
