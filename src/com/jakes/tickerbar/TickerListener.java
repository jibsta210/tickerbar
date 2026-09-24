package com.jakes.tickerbar;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;

/** Filters notifications and hands the survivors to the ticker. Owns no UI. */
public class TickerListener extends NotificationListenerService {

    private static TickerListener self;

    @Override public void onListenerConnected() { self = this; }
    @Override public void onListenerDisconnected() { if (self == this) self = null; }

    /** Dismiss a notification the user opened from the ticker, as the shade would. */
    static void cancel(String key) {
        TickerListener l = self;
        if (l == null || key == null) return;
        try { l.cancelNotification(key); } catch (Exception ignored) {}
    }

    /** Last text shown per notification key, so repeat updates don't re-ticker. */
    private final HashMap<String, String> last = new HashMap<String, String>();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        String key = sbn.getKey();

        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (getPackageName().equals(pkg)) return;
        if (Prefs.on(this, Prefs.SKIP_ONGOING)
                && (sbn.isOngoing() || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)) return;
        if (isIgnored(pkg)) return;
        if (Prefs.on(this, Prefs.SKIP_SILENT) && isSilent(key)) return;

        Bundle ex = n.extras;
        String title = text(ex, Notification.EXTRA_TITLE);
        String body = text(ex, Notification.EXTRA_TEXT);
        if (title.isEmpty() && body.isEmpty()) return;

        // an update the app itself says shouldn't alert again
        if ((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0 && last.containsKey(key)) {
            last.put(key, title + "\u0000" + body);
            return;
        }
        String sig = title + "\u0000" + body;
        if (sig.equals(last.get(key))) return;
        if (last.size() > 300) last.clear();
        last.put(key, sig);

        String app = label(pkg);
        ShadeService.post(new Item(app, title.isEmpty() ? app : title, body, pkg, n.getSmallIcon(),
                n.contentIntent, key, (n.flags & Notification.FLAG_AUTO_CANCEL) != 0));
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null) last.remove(sbn.getKey());
    }

    private boolean isSilent(String key) {
        RankingMap map = getCurrentRanking();
        if (map == null) return false;
        Ranking r = new Ranking();
        return map.getRanking(key, r) && r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT;
    }

    private boolean isIgnored(String pkg) {
        String list = Prefs.str(this, Prefs.IGNORE);
        if (list.isEmpty()) return false;
        for (String p : list.split("[,\\s]+")) if (p.trim().equals(pkg)) return true;
        return false;
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String text(Bundle ex, String k) {
        if (ex == null) return "";
        CharSequence cs = ex.getCharSequence(k);
        return cs == null ? "" : cs.toString().trim();
    }
}
