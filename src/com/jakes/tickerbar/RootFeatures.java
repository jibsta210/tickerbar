package com.jakes.tickerbar;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root-backed system integration that replaces HyperOS permission screens and hacks. */
final class RootFeatures {
    private static final String PREFS = "root_features";
    private static final String KEY_SHADE_RESTART_BOOT = "native_systemui_restart_boot_v11426";
    private static final AtomicBoolean APPLYING = new AtomicBoolean();
    private static final AtomicBoolean DETACHING = new AtomicBoolean();
    private static volatile boolean applied;

    private RootFeatures() {}

    static boolean applied() { return applied && RootShell.available(); }

    /** Idempotent: safe after boot, an app update, or reconnecting to the root daemon. */
    static void applyAsync(Context context) {
        if (!RootShell.available() || !APPLYING.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() { @Override public void run() {
            try { applied = apply(app); }
            finally { APPLYING.set(false); }
        }}, "TickerBar-root-features").start();
    }

    private static boolean apply(Context c) {
        RootPowerGestures.stop();
        // v1.14.33 routed this helper flag into the exploit. Clear every tagged
        // orphan before doing slower integration work; the fixed observer starts below.
        RootShell.Result powerCleanup = RootShell.exec("pkill -9 -f '[p]ower-helper' 2>/dev/null || true");
        if (!powerCleanup.connected)
            Log.w("TickerBarRoot", "Could not clean old power-helper processes");

        File resetprop = new File(c.getApplicationInfo().nativeLibraryDir, "libresetprop.so");
        if (!resetprop.isFile()) {
            Log.e("TickerBarRoot", "Bundled resetprop helper is missing");
            return false;
        }

        // resetprop has a multi-call entry point selected by argv[0]. A short-lived symlink
        // gives it the expected name without copying an executable out of the APK.
        String property = "ln -sf " + quote(resetprop.getAbsolutePath())
                + " /data/local/tmp/resetprop"
                + " && /data/local/tmp/resetprop -n debug.sysui.use_control_center -1"
                + " && /data/local/tmp/resetprop -n ro.config.media_vol_steps 150";
        RootShell.Result prop = RootShell.exec(property);
        if (!prop.connected || prop.code != 0) {
            Log.e("TickerBarRoot", "Legacy shade override failed: " + prop.output);
            return false;
        }

        String listener = new ComponentName(c, TickerListener.class).flattenToString();
        String recorderTile = "custom(com.jakes.tickerbar/.RecorderTileService)";
        String stockRecorderTile = "custom(com.miui.screenrecorder/.service.QuickService)";
        String powerTile = "custom(com.jakes.tickerbar/.PowerTileService)";
        String securityCountdown = "com.miui.securitycenter/"
                + "com.miui.permcenter.privacymanager.SpecialPermissionInterceptActivity";
        String command =
                // Trigger SystemUI's observed setting flow after installing the debug override.
                "settings put system use_control_panel 0; "
                + "settings delete secure sysui_nav_bar; "
                // Root-native navbar injection is safety-disabled. Restore HyperOS's
                // own long-press behavior and keep wallet/corner on the fallback UI.
                + "settings delete system long_press_home_key_no_ui; "
                // Grant TickerBar's own special access directly, without Security Center timers.
                + "cmd notification allow_listener " + quote(listener) + " 0; "
                + "cmd notification allow_dnd " + quote(c.getPackageName()) + " 0; "
                + "appops set " + quote(c.getPackageName()) + " SYSTEM_ALERT_WINDOW allow; "
                + "appops set " + quote(c.getPackageName()) + " GET_USAGE_STATS allow; "
                + "appops set " + quote(c.getPackageName()) + " WRITE_SETTINGS allow; "
                + "pm grant " + quote(c.getPackageName())
                + " android.permission.WRITE_SECURE_SETTINGS; "
                + "appops set " + quote(c.getPackageName()) + " REQUEST_INSTALL_PACKAGES allow; "
                + "appops set " + quote(c.getPackageName()) + " ACCESS_RESTRICTED_SETTINGS allow; "
                // Keep ADB authorization permanent and remove Xiaomi's separate install
                // confirmation/scan screens. The normal package installer and Play Protect
                // remain available; these keys only govern the vendor/ADB detours.
                + "settings put global adb_allowed_connection_time 0; "
                + "settings put global adb_install_need_confirm 0; "
                + "settings put global verifier_verify_adb_installs 0; "
                + "settings put system virus_scan_install 0; "
                // Repair HyperOS Recorder's incomplete first-run permissions.
                + "pm grant com.miui.screenrecorder android.permission.RECORD_AUDIO; "
                + "appops set com.miui.screenrecorder RECORD_AUDIO allow; "
                + "appops set com.miui.screenrecorder SYSTEM_ALERT_WINDOW allow; "
                // Keep the user's QS ordering, replacing only the broken vendor recorder tile.
                + "tiles=\"$(settings get secure sysui_qs_tiles)\"; "
                + "case \"$tiles\" in *" + quote(stockRecorderTile) + "*) "
                + "tiles=\"${tiles/" + stockRecorderTile + "/" + recorderTile + "}\"; "
                + "settings put secure sysui_qs_tiles \"$tiles\";; esac; "
                // Use Android's real Device Controls panel with Google Home as its provider.
                // HyperOS only embeds an inline Home list in its split control center; the
                // controls tile is the stable stock equivalent in the combined shade.
                + "case \",$tiles,\" in *,controls,*) ;; *) "
                + "tiles=\"controls,$tiles\"; settings put secure sysui_qs_tiles \"$tiles\";; esac; "
                // The first pull shows five columns. Put Power in its top-right slot,
                // but never override a position the user chooses later.
                + "case \",$tiles,\" in *," + quote(powerTile) + ",*) ;; *) "
                + "wrapped=\",$tiles,\"; case \"$wrapped\" in *,cell,*) "
                + "wrapped=\"${wrapped/,cell,/,cell," + powerTile + ",}\";; *) "
                + "wrapped=\"${wrapped%,}," + powerTile + ",\";; esac; "
                + "tiles=\"${wrapped#,}\"; tiles=\"${tiles%,}\"; "
                + "settings put secure sysui_qs_tiles \"$tiles\";; esac; "
                + "settings put secure miui_control_center_device_controller"
                + " com.google.android.apps.chromecast.app; "
                + "settings put secure lockscreen_show_controls 1; "
                + "settings put secure lockscreen_smart_device_control 1; "
                // Keep Android's footer flag enabled too. HyperOS currently omits that footer,
                // so PowerTileService provides the visible top-corner action.
                + "settings put global sysui_pm_lite 1; "
                // This is the separate Install-via-USB interceptor. Antivirus stays enabled.
                + "content call --uri content://com.miui.securitycenter.remoteprovider"
                + " --method callPreference --arg SET --extra type:i:1"
                + " --extra key:s:permcenter_install_intercept_enabled"
                + " --extra value:b:false >/dev/null; "
                // The stock 150-level stream handles keys, calls, casting and its own panel.
                // Retire the old EVIOCGRAB helper if an earlier build left it running.
                + "for f in /proc/[0-9]*/comm; do name=$(cat \"$f\" 2>/dev/null); "
                + "case \"$name\" in tickerbar-vol2|tickerbar-input*|tb-input) "
                + "p=${f#/proc/}; p=${p%/comm}; kill -TERM \"$p\";; esac; done";
        RootShell.Result result = RootShell.exec(command);
        if (!result.connected || result.code != 0) {
            Log.e("TickerBarRoot", "Root feature setup failed (" + result.code + "): " + result.output);
            return false;
        }
        // Settings checks that Xiaomi's intercept activity resolves before launching it and
        // falls back to Android's normal dialog if it does not. Disable only that hard-coded
        // 10-second activity, not the Security Center package. Keep this separate so a
        // firmware policy rejection is visible and cannot poison the rest of integration.
        RootShell.Result countdown = RootShell.exec(
                "pm disable --user 0 " + quote(securityCountdown));
        if (!countdown.connected || countdown.code != 0)
            Log.w("TickerBarRoot", "Security countdown component stayed enabled ("
                    + countdown.code + "): " + countdown.output);
        else Log.i("TickerBarRoot", "Security countdown component result: "
                + countdown.output.trim());
        installSystemUiForBoot(c);
        MemoryProfile.apply(c);
        applied = true;
        Log.i("TickerBarRoot", "Root system integration ready");
        TickerListener.filterActive();
        ShadeService.refresh();
        RootPowerGestures.sync(c);
        startNativeServices(c);
        RootOverlayClient.start(c);
        new Thread(new Runnable() { public void run() {
            for (int i = 0; i < 40; i++) {
                if (NativeTicker.ready()) return;
                try { Thread.sleep(250); } catch (InterruptedException ignored) { return; }
            }
        }}, "TickerBar-wait-native-ui").start();
        return true;
    }

    private static String removeNavOverlaysCommand() {
        StringBuilder command = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            String name = "TickerBarNav" + i;
            if (command.length() > 0) command.append("; ");
            command.append("cmd overlay disable --user 0 com.android.shell:")
                    .append(name).append(" >/dev/null 2>&1 || true")
                    .append("; cmd overlay delete com.android.shell:").append(name)
                    .append(" >/dev/null 2>&1 || true");
        }
        return command.toString();
    }

    /** HyperOS may reset special app-ops for a moment after replacing the APK. */
    private static void startNativeServices(final Context context) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() { public void run() {
            for (int i = 0; i < 8; i++) {
                RootShell.exec("appops set " + quote(app.getPackageName())
                        + " SYSTEM_ALERT_WINDOW allow; appops set " + quote(app.getPackageName())
                        + " GET_USAGE_STATS allow");
                NativeTicker.start(app);
                RootInput.stop();
                if (NativeTicker.ready()) return;
                try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
            }
        }}, "TickerBar-native-services").start();
    }

    /**
     * HyperOS snapshots debug.sysui.use_control_center when SystemUI starts. Root becomes
     * available after that process has already started, so restart only SystemUI once after
     * installing the override. The boot-count marker prevents repeated UI interruptions when
     * applyAsync is called again by tiles, services, or an app activity.
     */
    private static void installSystemUiForBoot(Context context) {
        int boot;
        try {
            boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Exception e) {
            Log.w("TickerBarRoot", "Cannot read boot count; leaving the current SystemUI intact", e);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt(KEY_SHADE_RESTART_BOOT, -1) == boot) return;

        RootShell.Result nav = RootShell.exec(removeNavOverlaysCommand());
        if (!nav.connected || nav.code != 0)
            Log.w("TickerBarRoot", "Navbar overlay cleanup failed (" + nav.code + "): " + nav.output);

        RootShell.Result restart = RootShell.exec(
                "pid=\"$(pidof com.android.systemui)\"; "
                        + "if [ -z \"$pid\" ]; then exit 3; fi; "
                        + "kill -TERM $pid");
        if (!restart.connected || restart.code != 0) {
            Log.w("TickerBarRoot", "SystemUI restart for stock single-page shade failed ("
                    + restart.code + "): " + restart.output);
            return;
        }

        prefs.edit().putInt(KEY_SHADE_RESTART_BOOT, boot).apply();
        Log.i("TickerBarRoot", "Restarted SystemUI for stock single-page shade on boot " + boot);
    }

    /** Opens HyperOS's actual floating recorder control, not its settings activity. */
    static void openRecorder(Context context, final ResultCallback callback) {
        final boolean root = RootShell.available();
        if (!root && (!Shell.running() || !Shell.granted())) {
            if (callback != null) callback.done(false, "Root or Shizuku is not ready");
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() { @Override public void run() {
            String command = "pm grant com.miui.screenrecorder android.permission.RECORD_AUDIO; "
                    + "appops set com.miui.screenrecorder RECORD_AUDIO allow; "
                    + "appops set com.miui.screenrecorder SYSTEM_ALERT_WINDOW allow; "
                    // The vendor service deliberately does not call startForeground(). Launch
                    // its translucent trampoline so it may start the service in the foreground.
                    + "am start --user 0"
                    + " -a miui.intent.screenrecorder.RECORDER_ACTIVITY"
                    + " -p com.miui.screenrecorder"
                    + " --ez is_start_immediately false"
                    + " --ez need_check_audio_permission false";
            int code;
            String output;
            if (root) {
                RootShell.Result result = RootShell.exec(command);
                code = result.connected ? result.code : -1;
                output = result.output;
            } else {
                Shell.Result result = Shell.runShizukuSync(command);
                code = result.code;
                output = result.output;
            }
            boolean ok = code == 0 && (output.contains("Starting:")
                    || output.contains("Warning: Activity not started"));
            if (callback != null) callback.done(ok, output.trim());
            if (ok) applyAsync(app);
        }}, "TickerBar-recorder").start();
    }

    interface ResultCallback { void done(boolean ok, String detail); }

    /** Retire only TickerBar's accessibility component after every replacement is live. */
    static void detachAccessibilityIfReady(final Context context) {
        if (!applied() || !NativeTicker.ready() || !RootInput.connected()) return;
        if (!DETACHING.compareAndSet(false, true)) return;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() { public void run() {
            try {
                String component = new ComponentName(app, ShadeService.class).flattenToString();
                String command = "services=\"$(settings get secure enabled_accessibility_services)\"; "
                        + "wrapped=\":$services:\"; "
                        + "wrapped=\"${wrapped/:" + component + ":/:}\"; "
                        + "new=\"${wrapped#:}\"; new=\"${new%:}\"; "
                        + "settings put secure enabled_accessibility_services \"$new\"; "
                        + "if [ -z \"$new\" ]; then settings put secure accessibility_enabled 0; fi";
                RootShell.Result result = RootShell.exec(command);
                if (result.connected && result.code == 0)
                    Log.i("TickerBarRoot", "Accessibility fallback retired; root UI owns all features");
                else Log.w("TickerBarRoot", "Could not retire accessibility: " + result.output);
            } finally { DETACHING.set(false); }
        }}, "TickerBar-retire-accessibility").start();
    }

    /** If root cannot return after a boot, Shizuku restores the old safe UI fallback. */
    static void restoreAccessibilityFallback(Context context) {
        if (RootShell.available() || !Shell.running() || !Shell.granted()) return;
        Context app = context.getApplicationContext();
        String component = new ComponentName(app, ShadeService.class).flattenToString();
        String command = "services=\"$(settings get secure enabled_accessibility_services)\"; "
                + "case \":$services:\" in *:" + component + ":*) ;; *) "
                + "services=\"${services:+$services:}" + component + "\"; "
                + "settings put secure enabled_accessibility_services \"$services\";; esac; "
                + "settings put secure accessibility_enabled 1; "
                + "pm grant com.miui.screenrecorder android.permission.RECORD_AUDIO; "
                + "appops set com.miui.screenrecorder RECORD_AUDIO allow; "
                + "appops set com.miui.screenrecorder SYSTEM_ALERT_WINDOW allow; "
                + "tiles=\"$(settings get secure sysui_qs_tiles)\"; "
                + "stock=\"custom(com.miui.screenrecorder/.service.QuickService)\"; "
                + "replacement=\"custom(com.jakes.tickerbar/.RecorderTileService)\"; "
                + "case \"$tiles\" in *\"$stock\"*) tiles=\"${tiles/$stock/$replacement}\"; "
                + "settings put secure sysui_qs_tiles \"$tiles\";; esac";
        Shell.runShizukuSync(command);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
