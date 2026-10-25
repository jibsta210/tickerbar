package com.jakes.tickerbar;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Runs adb-level shell commands through Shizuku, for the radio tiles Android won't let an
 * app switch (Wi-Fi, Bluetooth, mobile data, airplane mode, NFC). Shizuku itself is started
 * by the user from its own app; without it everything here reports unavailable and the
 * tiles fall back to opening settings.
 */
final class Shell {
    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    static final int REQUEST = 7301;

    private Shell() {}

    static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0); return true; }
        catch (Exception e) { return false; }
    }

    static boolean running() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    static boolean granted() {
        try {
            return running() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    static void requestPermission() {
        try { if (running()) Shizuku.requestPermission(REQUEST); } catch (Throwable ignored) {}
    }

    /** One line for settings: where Shizuku stands. */
    static String status(Context c) {
        if (!installed(c)) return "Shizuku isn't installed";
        if (!running()) return "Shizuku isn't running — start it in the Shizuku app";
        if (!granted()) return "Tap to allow TickerBar in Shizuku";
        return "✓ Ready: Wi-Fi, Bluetooth, mobile data, airplane mode, NFC and battery saver switch directly";
    }

    /** Run cmd with shell rights off the main thread, then call done (on the main thread). */
    static void run(final String cmd, final Runnable done) {
        new Thread(new Runnable() { public void run() {
            try {
                java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod("newProcess",
                        String[].class, String[].class, String.class);
                m.setAccessible(true);   // public API wants a bound service; a one-line command doesn't need one
                Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
                int code = p.waitFor();
                if (code != 0) Log.w("TickerBar", "shell `" + cmd + "` exited " + code);
            } catch (Throwable t) {
                Log.w("TickerBar", "shell `" + cmd + "` failed", t);
            }
            if (done != null) new Handler(Looper.getMainLooper()).post(done);
        }}, "TickerBar-shell").start();
    }
}
