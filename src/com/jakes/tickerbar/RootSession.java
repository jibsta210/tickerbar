package com.jakes.tickerbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** Explicit lifecycle for TickerBar's temporary, boot-scoped privileged session. */
final class RootSession {
    private static final String STORE = "root_session";
    private static final String KEY_CLEAN = "clean_boot_requested";
    private static final String KEY_DETAIL = "detail";

    interface Callback { void done(boolean ok, String detail); }

    private RootSession() {}

    static boolean cleanMode(Context context) {
        return prefs(context).getBoolean(KEY_CLEAN, false) && !RootShell.available();
    }

    static String cleanStatus(Context context) {
        return prefs(context).getString(KEY_DETAIL,
                "Clean boot · privileged daemon off · granted Android capabilities active");
    }

    static void markActive(Context context) {
        prefs(context).edit().putBoolean(KEY_CLEAN, false)
                .putString(KEY_DETAIL, "Privileged session active").apply();
    }

    /**
     * Preserve the persistent setup, undo boot-only tuning, terminate every root client,
     * remove temporary launch files, close the general daemon, then reboot.  Rebooting is
     * intentional: it is the only honest way to clear kernel/exploit state from this boot.
     */
    static void finishSetupAndReboot(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        if (!RootShell.available()) {
            deliver(callback, false, "No privileged session is running");
            return;
        }
        new Thread(new Runnable() { @Override public void run() {
            try {
                if (RootShell.restricted()) {
                    NativeTicker.stop();
                    RootInput.stop();
                    RootPowerGestures.stop();
                    RootOverlayClient.stop();
                    prefs(app).edit().putBoolean(KEY_CLEAN, true)
                            .putString(KEY_DETAIL,
                                    "Clean boot · privileged daemon off · Android grants retained")
                            .commit();
                    RootShell.Result reboot = RootShell.operation("clean_reboot");
                    boolean scheduled = reboot.connected && reboot.code == 0;
                    if (scheduled) RootShell.shutdownDaemon();
                    RootFeatures.privilegeDropped();
                    deliver(callback, scheduled, scheduled
                            ? "Allowlisted worker stopped; rebooting clean"
                            : "Could not schedule the clean reboot");
                    return;
                }
                RootFeatures.applyAsync(app);
                long deadline = android.os.SystemClock.elapsedRealtime() + 20_000L;
                while ((RootFeatures.applying() || !RootFeatures.applied())
                        && RootShell.available()
                        && android.os.SystemClock.elapsedRealtime() < deadline) {
                    try { Thread.sleep(200); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        deliver(callback, false, "Clean transition was interrupted");
                        return;
                    }
                }
                if (RootFeatures.applying() || !RootFeatures.applied()) {
                    deliver(callback, false,
                            "Persistent setup did not finish; root was left active");
                    return;
                }

                // Memory changes are deliberately boot-scoped and are not part of the
                // persistent permission/status-bar setup retained in clean mode.
                MemoryProfile.restoreStock(app);

                NativeTicker.stop();
                RootInput.stop();
                RootPowerGestures.stop();
                RootOverlayClient.stop();
                try { Thread.sleep(650); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                RootShell.Result cleanup = RootShell.exec(
                        "cmd statusbar send-disable-flag none; "
                        + "for f in /proc/[0-9]*/comm; do "
                        + "name=$(cat \"$f\" 2>/dev/null); case \"$name\" in "
                        + "tb-input3|tb-power|tickerbar-vol2) "
                        + "p=${f#/proc/}; p=${p%/comm}; kill -TERM \"$p\";; esac; done; "
                        + "rm -f /data/local/tmp/resetprop "
                        + "/data/local/tmp/tickerbar-status-icons "
                        + "/data/local/tmp/ghostlock-songyuan-root; sync");
                if (!cleanup.connected || cleanup.code != 0) {
                    deliver(callback, false,
                            "Cleanup failed; root was left active (" + cleanup.code + ")");
                    return;
                }

                prefs(app).edit().putBoolean(KEY_CLEAN, true)
                        .putString(KEY_DETAIL,
                                "Clean boot · privileged daemon off · Android grants retained")
                        .commit();

                // Schedule the clean reboot before terminating the daemon.  The protocol
                // shutdown is independent of the shell job and closes the root socket now.
                RootShell.Result reboot = RootShell.exec(
                        "nohup /system/bin/sh -c 'sleep 2; /system/bin/svc power reboot' "
                        + ">/dev/null 2>&1 &");
                if (!reboot.connected || reboot.code != 0) {
                    prefs(app).edit().putBoolean(KEY_CLEAN, false)
                            .putString(KEY_DETAIL, "Privileged session active").apply();
                    deliver(callback, false, "Could not schedule the clean reboot");
                    return;
                }

                boolean stopped = RootShell.shutdownDaemon();
                RootFeatures.privilegeDropped();
                deliver(callback, stopped, stopped
                        ? "Root worker stopped; rebooting clean"
                        : "Reboot scheduled; root worker will end during reboot");
            } catch (Throwable failure) {
                Log.e("TickerBarRoot", "Clean-session transition failed", failure);
                deliver(callback, false, "Clean transition failed: "
                        + failure.getClass().getSimpleName());
            }
        }}, "TickerBar-drop-root").start();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    private static void deliver(final Callback callback, final boolean ok, final String detail) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(new Runnable() { @Override public void run() {
            callback.done(ok, detail);
        }});
    }
}
