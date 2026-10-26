package com.jakes.tickerbar;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

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

    static final class Result {
        final int code;
        final String output;
        Result(int code, String output) { this.code = code; this.output = output; }
    }

    interface ResultCallback { void done(Result result); }

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

    /** A privileged command channel is ready; root is preferred over Shizuku. */
    static boolean privileged() {
        return RootShell.available() || granted();
    }

    static void requestPermission() {
        try { if (running()) Shizuku.requestPermission(REQUEST); } catch (Throwable ignored) {}
    }

    /** One line for settings: where Shizuku stands. */
    static String status(Context c) {
        if (RootShell.available()) return "✓ Root daemon ready · Shizuku available as fallback";
        if (!installed(c)) return "Shizuku isn't installed";
        if (!running()) return "Shizuku isn't running — start it in the Shizuku app";
        if (!granted()) return "Tap to allow TickerBar in Shizuku";
        return "✓ Ready: Wi-Fi, Bluetooth, mobile data, airplane mode, NFC and battery saver switch directly";
    }

    /** Run cmd with shell rights off the main thread, then call done (on the main thread). */
    static void run(final String cmd, final Runnable done) {
        runResult(cmd, new ResultCallback() { public void done(Result result) {
            if (done != null) done.run();
        }});
    }

    /** Run a privileged command and return its exit status on the main thread. */
    static void runResult(final String cmd, final ResultCallback done) {
        new Thread(new Runnable() { public void run() {
            Result result;
            if (RootShell.available()) {
                RootShell.Result root = RootShell.exec(cmd);
                result = new Result(root.connected ? root.code : -1, root.output);
            } else {
                result = runShizukuSync(cmd);
            }
            if (result.code != 0) Log.w("TickerBar", "shell `" + cmd + "` exited "
                    + result.code + ": " + result.output);
            final Result delivered = result;
            if (done != null) new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() { done.done(delivered); }
            });
        }}, "TickerBar-shell").start();
    }

    /** Explicit Shizuku path used only to launch the bundled bootstrap. */
    static Result runShizukuSync(String cmd) {
        try {
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            final Process p = (Process) m.invoke(null,
                    new String[]{"sh"}, null, null);
            final StringBuilder output = new StringBuilder();
            Thread stdout = drain(p.getInputStream(), output);
            Thread stderr = drain(p.getErrorStream(), output);
            OutputStream stdin = p.getOutputStream();
            stdin.write((cmd + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            stdin.close();
            int code = p.waitFor();
            stdout.join(2_000);
            stderr.join(2_000);
            return new Result(code, output.toString());
        } catch (Throwable t) {
            return new Result(-1, t.toString());
        }
    }

    private static Thread drain(final InputStream in, final StringBuilder output) {
        Thread t = new Thread(new Runnable() { public void run() {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) > 0) synchronized (output) {
                    output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    if (output.length() > 64 * 1024)
                        output.delete(0, output.length() - 64 * 1024);
                }
            } catch (Throwable ignored) {}
        }}, "TickerBar-shell-output");
        t.start();
        return t;
    }
}
