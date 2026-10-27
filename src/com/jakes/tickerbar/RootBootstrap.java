package com.jakes.tickerbar;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** Owns the one-way Shizuku -> native bootstrap -> root-daemon transition. */
final class RootBootstrap {
    // The songyuan route now uses perf-based task discovery, the bounded
    // right-child credential write, and an explicit post-route ghost disarm.
    // Shizuku remains available as the bootstrap/fallback transport.
    private static final boolean ROOT_ATTEMPTS_ENABLED = true;
    private static final String PREF = "tickerbar_root";
    private static final String KEY_STATE = "state";
    private static final String KEY_DETAIL = "detail";
    private static final String KEY_READY_AT = "ready_at";
    private static final String KEY_ATTEMPT_BOOT = "attempt_boot";
    private static final AtomicBoolean initialized = new AtomicBoolean();
    private static final AtomicBoolean busy = new AtomicBoolean();
    private static volatile Context app;
    private static volatile long lastAttempt;
    private static volatile boolean userRequested;

    private RootBootstrap() {}

    static void start(Context context) {
        app = context.getApplicationContext();
        if (initialized.compareAndSet(false, true)) {
            try {
                Shizuku.addBinderReceivedListenerSticky(
                        new Shizuku.OnBinderReceivedListener() {
                            @Override public void onBinderReceived() {
                                RootFeatures.restoreAccessibilityFallback(app);
                                if (userRequested) kick();
                            }
                        });
                Shizuku.addRequestPermissionResultListener(
                        new Shizuku.OnRequestPermissionResultListener() {
                            @Override public void onRequestPermissionResult(int requestCode, int result) {
                                if (requestCode == Shell.REQUEST && userRequested) kick();
                            }
                        });
            } catch (Throwable t) {
                Log.w("TickerBarRoot", "Shizuku listeners unavailable", t);
            }
        }
        if (RootShell.available()) {
            state(app, "ready", "Root daemon is ready");
            RootFeatures.applyAsync(app);
        } else if (!ROOT_ATTEMPTS_ENABLED) {
            state(app, "disabled",
                    "Root attempts paused after kernel panic — normal TickerBar is safe");
            RootFeatures.restoreAccessibilityFallback(app);
        } else if (!userRequested) {
            int boot = Settings.Global.getInt(app.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (boot >= 0 && prefs(app).getInt(KEY_ATTEMPT_BOOT, -2) == boot)
                state(app, "reboot_required",
                        "Root did not start this boot — reboot before the next attempt");
            else state(app, "idle", "Idle — tap Start root attempt");
            RootFeatures.restoreAccessibilityFallback(app);
        }
    }

    /** Arms exactly one bootstrap attempt from the root menu. */
    static void request(Context context) {
        if (!ROOT_ATTEMPTS_ENABLED) {
            app = context.getApplicationContext();
            state(app, "disabled",
                    "Root attempts paused after kernel panic — normal TickerBar is safe");
            return;
        }
        userRequested = true;
        start(context);
        kick();
    }

    static void kick() {
        final Context c = app;
        if (c == null || busy.get() || !userRequested) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastAttempt < 2_000) return;
        lastAttempt = now;
        new Thread(new Runnable() { @Override public void run() { bootstrap(c); } },
                "TickerBar-root-bootstrap").start();
    }

    private static void bootstrap(Context c) {
        if (!busy.compareAndSet(false, true)) return;
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (power != null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "TickerBar:root-bootstrap");
                wakeLock.acquire(25 * 60 * 1000L);
            }
            if (RootShell.available()) {
                state(c, "ready", "Root daemon is ready");
                return;
            }
            if (!"songyuan".equals(Build.DEVICE)) {
                state(c, "unsupported", "Root module is built only for POCO F9 Ultra (songyuan)");
                return;
            }
            int bootCount = Settings.Global.getInt(c.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
            if (bootCount >= 0 && prefs(c).getInt(KEY_ATTEMPT_BOOT, -2) == bootCount) {
                state(c, "reboot_required",
                        "One attempt already ran this boot — reboot before retrying");
                userRequested = false;
                return;
            }
            if (!Shell.running()) {
                state(c, "waiting_shizuku", "Waiting for Shizuku after this boot");
                return;
            }
            if (!Shell.granted()) {
                state(c, "waiting_permission", "Allow TickerBar in Shizuku once");
                return;
            }

            File payload = new File(c.getApplicationInfo().nativeLibraryDir,
                    "libtickerroot.so");
            if (!payload.isFile()) {
                state(c, "missing", "Bundled native root module is missing");
                return;
            }

            state(c, "bootstrapping", "Preparing root daemon; keep the phone awake");
            Shell.Result preflight = Shell.runShizukuSync(
                    "printf TICKERBAR_SHIZUKU_EXEC_OK");
            if (preflight.code != 0
                    || !"TICKERBAR_SHIZUKU_EXEC_OK".equals(preflight.output)) {
                state(c, "failed", "Shizuku command preflight failed (exit "
                        + preflight.code + ")");
                Log.w("TickerBarRoot", "preflight output=" + preflight.output);
                return;
            }
            // songyuan runs a 6.12 kernel. Its reliable page capture is the
            // v6/Oppo reclaim teardown; the v5.10 hold-all profile misses the
            // first KASLR write on this build.
            String command = "export OPPO_RECLAIM_TEARDOWN=1 TICKERBAR_ROOT_ARMED=1 TICKERBAR_DAEMON=1 TICKERBAR_STAGE_LOG=/data/local/tmp/tickerbar-root-stage-"
                    + bootCount + ".log TICKERBAR_ALLOWED_UID="
                    + Process.myUid() + "; exec " + quote(payload.getAbsolutePath());
            if (bootCount >= 0)
                prefs(c).edit().putInt(KEY_ATTEMPT_BOOT, bootCount).apply();
            Shell.Result result = Shell.runShizukuSync(command);
            saveAttemptLog(c, result);
            Log.i("TickerBarRoot", "bootstrap exit=" + result.code + " output=" + result.output);

            boolean ready = false;
            for (int i = 0; i < 20 && !ready; i++) {
                ready = RootShell.available();
                if (!ready) try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            if (!ready) {
                state(c, "failed", "Root bootstrap failed (exit " + result.code + ")");
                return;
            }

            prefs(c).edit().putLong(KEY_READY_AT, System.currentTimeMillis()).apply();
            state(c, "ready", "Root daemon is ready; Shizuku remains available as fallback");
            RootFeatures.applyAsync(c);
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            if (!RootShell.available()) RootFeatures.restoreAccessibilityFallback(c);
            busy.set(false);
        }
    }

    static String status(Context c) {
        if (!ROOT_ATTEMPTS_ENABLED)
            return prefs(c).getString(KEY_DETAIL,
                    "Root attempts paused after kernel panic — normal TickerBar is safe");
        if (RootShell.available()) return "✓ Root daemon ready · Shizuku fallback kept";
        SharedPreferences p = prefs(c);
        String detail = p.getString(KEY_DETAIL, "Idle — tap Start root attempt");
        return detail == null ? "Idle — tap Start root attempt" : detail;
    }

    static boolean isBusy() { return busy.get(); }
    static boolean attemptsEnabled() { return ROOT_ATTEMPTS_ENABLED; }

    private static void state(Context c, String state, String detail) {
        prefs(c).edit().putString(KEY_STATE, state).putString(KEY_DETAIL, detail).apply();
        Log.i("TickerBarRoot", state + ": " + detail);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void saveAttemptLog(Context c, Shell.Result result) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        File out = new File(dir, "root-last-attempt.log");
        String text = "timestamp=" + System.currentTimeMillis() + "\n"
                + "exit=" + result.code + "\n" + result.output + "\n";
        try (FileOutputStream stream = new FileOutputStream(out, false)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w("TickerBarRoot", "Could not persist root attempt log", e);
        }
    }
}
