package com.jakes.tickerbar;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live, restart-free controls backed by the settings observed by this HyperOS SystemUI. */
final class SystemBarSettings {
    interface Callback { void done(boolean ok); }

    private static final ExecutorService WRITES = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SystemBarSettings() {}

    static boolean slotsVisible(Context context, String... slots) {
        Set<String> hidden = hidden(context);
        for (String slot : slots) if (hidden.contains(slot)) return false;
        return true;
    }

    static void setSlotsVisible(final Context context, final String[] slots,
                                final boolean visible, final Callback callback) {
        final Context app = context.getApplicationContext();
        WRITES.execute(new Runnable() { @Override public void run() {
            Set<String> hidden = hidden(app);
            for (String slot : slots) {
                if (visible) hidden.remove(slot); else hidden.add(slot);
            }
            finish(callback, put(app, "secure", "icon_blacklist", TextUtils.join(",", hidden)));
        }});
    }

    static int getInt(Context context, String table, String key, int fallback) {
        try {
            if ("secure".equals(table))
                return Settings.Secure.getInt(context.getContentResolver(), key, fallback);
            if ("global".equals(table))
                return Settings.Global.getInt(context.getContentResolver(), key, fallback);
            return Settings.System.getInt(context.getContentResolver(), key, fallback);
        } catch (Exception ignored) { return fallback; }
    }

    static String nameFor(Context context, String table, String key, int fallback,
                          int[] values, String[] names) {
        int current = getInt(context, table, key, fallback);
        for (int i = 0; i < values.length; i++) if (values[i] == current) return names[i];
        return String.valueOf(current);
    }

    static void putInt(final Context context, final String table, final String key,
                       final int value, final Callback callback) {
        final Context app = context.getApplicationContext();
        WRITES.execute(new Runnable() { @Override public void run() {
            finish(callback, put(app, table, key, String.valueOf(value)));
        }});
    }

    private static Set<String> hidden(Context context) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String value = Settings.Secure.getString(context.getContentResolver(), "icon_blacklist");
        if (value == null || "null".equals(value)) return out;
        for (String slot : value.split(",")) if (!slot.trim().isEmpty()) out.add(slot.trim());
        return out;
    }

    private static boolean put(Context context, String table, String key, String value) {
        try {
            if ("secure".equals(table)
                    && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED)
                return Settings.Secure.putString(context.getContentResolver(), key, value);
            if ("system".equals(table) && Settings.System.canWrite(context))
                return Settings.System.putString(context.getContentResolver(), key, value);
        } catch (Throwable ignored) {}

        String command = "settings put " + table + " " + quote(key) + " " + quote(value);
        if (RootShell.available()) {
            RootShell.Result result = RootShell.exec(command);
            return result.connected && result.code == 0;
        }
        if (Shell.granted()) return Shell.runShizukuSync(command).code == 0;
        return false;
    }

    private static void finish(final Callback callback, final boolean ok) {
        if (callback != null) MAIN.post(new Runnable() { @Override public void run() {
            callback.done(ok);
        }});
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
