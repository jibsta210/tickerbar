package com.jakes.tickerbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/** Reversible, boot-scoped control of HyperOS's extra process-killing layer. */
final class MemoryProfile {
    private static final String STORE = "memory_profile_stock";
    private static final String[] PROPS = {
            "persist.sys.spc.enabled",
            "persist.sys.spc.extra_free_enable",
            "persist.sys.miui.unfairMemory.enable"
    };

    private MemoryProfile() {}

    static void applyAsync(Context context) {
        if (!RootShell.available()) return;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() { public void run() { apply(app); }},
                "TickerBar-memory-profile").start();
    }

    static boolean apply(Context context) {
        File resetprop = new File(context.getApplicationInfo().nativeLibraryDir, "libresetprop.so");
        if (!resetprop.isFile() || !RootShell.available()) return false;
        saveStockOnce(context);
        boolean relaxed = Prefs.on(context, Prefs.MEMORY_RELAXED);
        SharedPreferences stock = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        StringBuilder command = new StringBuilder("ln -sf ")
                .append(quote(resetprop.getAbsolutePath())).append(" /data/local/tmp/resetprop");
        for (String prop : PROPS) {
            String value = relaxed ? "false" : stock.getString(prop, "true");
            command.append("; /data/local/tmp/resetprop -n ").append(quote(prop)).append(' ')
                    .append(quote(value));
        }
        String freezer = stock.getString("cached_apps_freezer", "disabled");
        if (relaxed) command.append("; settings put global cached_apps_freezer disabled");
        else if ("null".equals(freezer) || freezer.isEmpty())
            command.append("; settings delete global cached_apps_freezer");
        else command.append("; settings put global cached_apps_freezer ").append(quote(freezer));

        // Keep TickerBar alive enough to own its listener/ticker. These commands are
        // package-scoped and do not weaken Android's low-memory killer.
        if (relaxed) command.append("; cmd deviceidle whitelist +")
                .append(context.getPackageName())
                .append("; am set-standby-bucket ").append(context.getPackageName()).append(" active");
        else command.append("; cmd deviceidle whitelist -").append(context.getPackageName());
        RootShell.Result result = RootShell.exec(command.toString());
        if (!result.connected || result.code != 0) {
            Log.w("TickerBarMemory", "Memory profile failed: " + result.output);
            return false;
        }
        Log.i("TickerBarMemory", relaxed
                ? "HyperOS extra memory-kill layer disabled for this boot"
                : "Stock HyperOS memory properties restored");
        return true;
    }

    private static void saveStockOnce(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        if (prefs.getBoolean("saved", false)) return;
        StringBuilder query = new StringBuilder();
        for (String prop : PROPS)
            query.append("printf '").append(prop).append("='; getprop ").append(prop).append("; ");
        query.append("printf 'cached_apps_freezer='; settings get global cached_apps_freezer");
        RootShell.Result result = RootShell.exec(query.toString());
        if (!result.connected || result.code != 0) return;
        SharedPreferences.Editor edit = prefs.edit();
        for (String line : result.output.split("\\n")) {
            int split = line.indexOf('=');
            if (split <= 0) continue;
            String key = line.substring(0, split).trim();
            String value = line.substring(split + 1).trim();
            if (!key.isEmpty()) edit.putString(key, value);
        }
        edit.putBoolean("saved", true).apply();
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
