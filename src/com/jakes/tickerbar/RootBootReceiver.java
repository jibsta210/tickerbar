package com.jakes.tickerbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the coordinator after boot/update. Kernel attempts are always user initiated. */
public class RootBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Shortcuts.publish(context);
        // v1.14.33 briefly prototyped a launcher-only Home notification. Remove it;
        // the real location-aware light card is not represented by that placeholder.
        android.app.NotificationManager notifications = (android.app.NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications != null) {
            notifications.cancel(0x47484f4d);
            notifications.deleteNotificationChannel("google_home_controls");
        }
        RootFeatures.ensureShadeHost(context);
        RootBootstrap.start(context);
    }
}
