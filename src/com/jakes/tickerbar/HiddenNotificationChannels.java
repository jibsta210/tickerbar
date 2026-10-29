package com.jakes.tickerbar;

import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps SystemUI status icons consistent with TickerBar's hidden app/channel rules. */
final class HiddenNotificationChannels {
    private static final String PREF = "tickerbar_hidden_channel_importance";
    private static final String KEY = "originals";
    private static final int USER_ALL = -1;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private HiddenNotificationChannels() {}

    static void hide(final TickerListener listener, final StatusBarNotification sbn) {
        if (listener == null || sbn == null || sbn.getNotification() == null
                || !RootShell.available()) return;
        final String pkg = sbn.getPackageName();
        final String channelId = sbn.getNotification().getChannelId();
        if (pkg == null || channelId == null) return;
        final int uid = sbn.getUid();
        final int userId = sbn.getUserId();
        final int notificationId = sbn.getId();
        final String notificationTag = sbn.getTag();
        final Context app = listener.getApplicationContext();
        WORK.execute(new Runnable() { @Override public void run() {
            int original = setImportance(app, pkg, uid, channelId,
                    NotificationManager.IMPORTANCE_MIN, true);
            if (original > NotificationManager.IMPORTANCE_MIN) {
                remember(app, pkg, uid, channelId, original);
                Log.i("TickerBarFilter", "Suppressed status icon: " + pkg
                        + " channel=" + channelId + " importance=" + original + "->1");
            }
            // A handful of framework notices are posted to USER_ALL. Android does not
            // rerank those when user 0's channel changes, and listener cancellation rejects
            // their ongoing flag. Root-cancel only that special system-wide record; normal
            // foreground-service notifications stay alive at minimum channel importance.
            if (userId == USER_ALL)
                cancelSystemWide(app, pkg, notificationTag, notificationId, userId);
        }});
    }

    /** Restore saved channel importance once neither the app nor this channel is hidden. */
    static void restore(final Context context, final String packageName, final String channelId) {
        if (context == null || packageName == null) return;
        final Context app = context.getApplicationContext();
        WORK.execute(new Runnable() { @Override public void run() {
            List<Record> records = records(app);
            List<Record> keep = new ArrayList<Record>();
            for (Record record : records) {
                boolean target = record.pkg.equals(packageName)
                        && (channelId == null || record.channel.equals(channelId));
                boolean stillHidden = Prefs.hidden(app, record.pkg)
                        || Prefs.typeHidden(app, record.pkg, record.channel);
                if (!target || stillHidden || !RootShell.available()
                        || setImportance(app, record.pkg, record.uid,
                                record.channel, record.importance, false) < 0) {
                    keep.add(record);
                    continue;
                }
                Log.i("TickerBarFilter", "Restored status icon channel: " + record.pkg
                        + " channel=" + record.channel + " importance=" + record.importance);
            }
            write(app, keep);
        }});
    }

    /** Returns the channel's previous importance, or -1 when the root operation failed. */
    private static int setImportance(Context context, String pkg, int uid,
                                     String channel, int importance, boolean lowerOnly) {
        String apk = context.getApplicationInfo().sourceDir;
        String command = "CLASSPATH=" + quote(apk)
                + " /system/bin/app_process /system/bin com.jakes.tickerbar.RootTool"
                + " notification-channel " + quote(pkg) + " " + uid + " "
                + quote(channel) + " " + importance + " " + (lowerOnly ? "lower" : "set");
        RootShell.Result result = RootShell.exec(command);
        if (result.connected && result.code == 0) {
            for (String line : result.output.split("\n")) if (line.startsWith("old=")) try {
                return Integer.parseInt(line.substring(4).trim());
            } catch (NumberFormatException ignored) {}
        }
        Log.w("TickerBarFilter", "Could not update channel " + pkg + "/" + channel
                + " (" + result.code + "): " + result.output);
        return -1;
    }

    private static void cancelSystemWide(Context context, String pkg, String tag,
                                         int id, int userId) {
        String apk = context.getApplicationInfo().sourceDir;
        String command = "CLASSPATH=" + quote(apk)
                + " /system/bin/app_process /system/bin com.jakes.tickerbar.RootTool"
                + " cancel-notification " + quote(pkg) + " "
                + quote(tag == null ? "@null" : tag) + " " + id + " " + userId;
        RootShell.Result result = RootShell.exec(command);
        if (result.connected && result.code == 0) {
            Log.i("TickerBarFilter", "Removed hidden system-wide status notice: " + pkg
                    + " id=" + id);
            return;
        }
        Log.w("TickerBarFilter", "Could not remove system-wide notice " + pkg + "/" + id
                + " (" + result.code + "): " + result.output);
    }

    private static void remember(Context context, String pkg, int uid, String channel, int importance) {
        List<Record> all = records(context);
        for (Record record : all)
            if (record.pkg.equals(pkg) && record.channel.equals(channel)) return;
        all.add(new Record(pkg, uid, channel, importance));
        write(context, all);
    }

    private static List<Record> records(Context context) {
        ArrayList<Record> out = new ArrayList<Record>();
        String saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "");
        for (String line : saved.split("\n")) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) continue;
            try {
                out.add(new Record(decode(fields[0]), Integer.parseInt(fields[1]),
                        decode(fields[2]), Integer.parseInt(fields[3])));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void write(Context context, List<Record> records) {
        StringBuilder saved = new StringBuilder();
        for (Record record : records)
            saved.append(encode(record.pkg)).append('\t').append(record.uid).append('\t')
                    .append(encode(record.channel)).append('\t').append(record.importance).append('\n');
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY, saved.toString()).apply();
    }

    private static String encode(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.NO_WRAP | Base64.URL_SAFE),
                StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class Record {
        final String pkg, channel;
        final int uid, importance;
        Record(String pkg, int uid, String channel, int importance) {
            this.pkg = pkg;
            this.uid = uid;
            this.channel = channel;
            this.importance = importance;
        }
    }
}
