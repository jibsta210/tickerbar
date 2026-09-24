package com.jakes.tickerbar;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Only forwards notifications to BarService; owns no UI. */
public class TickerListener extends NotificationListenerService {

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (getPackageName().equals(sbn.getPackageName())) return;

        Bundle ex = n.extras;
        CharSequence t = ex != null ? ex.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence b = ex != null ? ex.getCharSequence(Notification.EXTRA_TEXT) : null;
        BarService.show(this,
                t != null ? t.toString() : sbn.getPackageName(),
                b != null ? b.toString() : "");
    }
}
