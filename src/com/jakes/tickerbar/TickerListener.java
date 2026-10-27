package com.jakes.tickerbar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Filters notifications and hands the survivors to the ticker. Owns no UI. */
public class TickerListener extends NotificationListenerService {

    private static TickerListener self;

    @Override public void onListenerConnected() {
        self = this;
        Shortcuts.publish(this);
        RootBootstrap.start(this);
        NativeTicker.start(this);
        RootInput.stop();
        RootOverlayClient.start(this);
        try {
            StatusBarNotification[] current = getActiveNotifications();
            if (current != null) for (StatusBarNotification sbn : current) {
                if (sbn == null || getPackageName().equals(sbn.getPackageName())) continue;
                remember(sbn.getPackageName());
                rememberType(sbn);
                hideFromStockShadeIfSelected(sbn);
            }
        } catch (Exception ignored) {}
    }
    @Override public void onListenerDisconnected() {
        NativeTicker.stop();
        RootOverlayClient.stop();
        if (self == this) self = null;
    }

    @Override public void onDestroy() {
        NativeTicker.stop();
        RootOverlayClient.stop();
        if (self == this) self = null;
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        NativeTicker.refresh(this);
    }

    /** Dismiss a notification the user opened from the ticker, as the shade would. */
    static void cancel(String key) {
        TickerListener l = self;
        if (l == null || key == null) return;
        try { l.cancelNotification(key); } catch (Exception ignored) {}
    }

    /** Run the notification's real PendingIntent when the root ticker is tapped. */
    static void open(Context context, String key, String pkg, boolean autoCancel) {
        TickerListener listener = self;
        boolean opened = false;
        if (listener != null && key != null) {
            PendingIntent pending = listener.intents.get(key);
            if (pending != null) try {
                pending.send();
                opened = true;
            } catch (PendingIntent.CanceledException ignored) {}
        }
        if (!opened && pkg != null && !pkg.isEmpty()) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { context.startActivity(launch); } catch (Exception ignored) {}
            }
        }
        if (autoCancel) cancel(key);
    }

    /** Everything currently posted, for the shade; null without notification access. */
    static StatusBarNotification[] active() {
        TickerListener l = self;
        if (l == null) return null;
        try { return l.getActiveNotifications(); } catch (Exception e) { return null; }
    }

    static RankingMap ranking() {
        TickerListener l = self;
        if (l == null) return null;
        try { return l.getCurrentRanking(); } catch (Exception e) { return null; }
    }

    /** Apply the user's app/channel exclusions to HyperOS's real stock shade. */
    static void filterActive() {
        TickerListener listener = self;
        if (listener == null || !RootFeatures.applied()) return;
        StatusBarNotification[] active;
        try { active = listener.getActiveNotifications(); }
        catch (Exception e) { return; }
        if (active == null) return;
        for (StatusBarNotification sbn : active) listener.hideFromStockShadeIfSelected(sbn);
    }

    /** Snooze from the shade's long-press menu; it comes back after ms. */
    static void snooze(String key, long ms) {
        TickerListener l = self;
        if (l == null || key == null) return;
        try { l.snoozeNotification(key, ms); } catch (Exception ignored) {}
    }

    /** The shade's Clear all: what the user could swipe away, nothing ongoing. */
    static void cancelAll() {
        TickerListener l = self;
        if (l == null) return;
        try { l.cancelAllNotifications(); } catch (Exception ignored) {}
    }

    /** Last text shown per notification key, so repeat updates don't re-ticker. */
    private final HashMap<String, String> last = new HashMap<String, String>();
    private final ConcurrentHashMap<String, PendingIntent> intents =
            new ConcurrentHashMap<String, PendingIntent>();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        ShadeService.notificationsChanged();
        if (sbn == null || sbn.getNotification() == null) return;
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        String key = sbn.getKey();

        if (!getPackageName().equals(pkg)) remember(pkg);
        rememberType(sbn);
        if (hideFromStockShadeIfSelected(sbn)) return;
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
        if (n.contentIntent != null) intents.put(key, n.contentIntent);
        else intents.remove(key);

        String app = label(pkg);
        Item item = new Item(app, title.isEmpty() ? app : title, body, pkg, n.getSmallIcon(),
                n.contentIntent, key, (n.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        if (!NativeTicker.post(item) && !RootOverlayClient.post(item)) ShadeService.post(item);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        last.remove(sbn.getKey());
        intents.remove(sbn.getKey());
        RootOverlayClient.recall(sbn.getKey());
        NativeTicker.recall(sbn.getKey());
        ShadeService.notificationsChanged();
        // read in the app, swiped away, or replaced: the ticker shouldn't keep showing it
        ShadeService.recall(sbn.getKey());
    }

    private boolean hideFromStockShadeIfSelected(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || !RootFeatures.applied()) return false;
        String pkg = sbn.getPackageName();
        if (getPackageName().equals(pkg)) return false;
        Notification n = sbn.getNotification();
        if (!Prefs.hidden(this, pkg) && !Prefs.typeHidden(this, pkg, n.getChannelId())) return false;
        try {
            cancelNotification(sbn.getKey());
            Log.i("TickerBarFilter", "Removed from stock shade: " + pkg
                    + " channel=" + n.getChannelId());
        } catch (Exception failure) {
            Log.w("TickerBarFilter", "Could not remove " + pkg, failure);
        }
        return true;
    }

    private boolean isSilent(String key) {
        RankingMap map = getCurrentRanking();
        if (map == null) return false;
        Ranking r = new Ranking();
        return map.getRanking(key, r) && r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT;
    }

    private final java.util.HashSet<String> remembered = new java.util.HashSet<String>();
    private final java.util.HashSet<String> rememberedTypes = new java.util.HashSet<String>();

    /** Keep a list of apps that notify, so settings can offer them for hiding. */
    private void remember(String pkg) {
        if (!remembered.add(pkg)) return;
        java.util.Set<String> seen = Prefs.set(this, Prefs.SEEN_APPS);
        if (seen.add(pkg)) {
            while (seen.size() > 300) seen.remove(seen.iterator().next());
            Prefs.putSet(this, Prefs.SEEN_APPS, seen);
        }
    }

    private void rememberType(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        String channelId = n == null ? null : n.getChannelId();
        if (channelId == null) return;
        String identity = sbn.getPackageName() + "\t" + channelId;
        if (!rememberedTypes.add(identity)) return;
        String name = channelId;
        try {
            Ranking ranking = new Ranking();
            RankingMap map = getCurrentRanking();
            if (map != null && map.getRanking(sbn.getKey(), ranking)) {
                NotificationChannel channel = ranking.getChannel();
                if (channel != null && channel.getName() != null) name = channel.getName().toString();
            }
        } catch (Throwable ignored) {}
        Prefs.rememberType(this, sbn.getPackageName(), channelId, name);
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
