package com.jakes.tickerbar;

import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional passive KEY_POWER observer backed by the authenticated root daemon. */
final class RootPowerGestures {
    static final int ACTION_NONE = 0;
    static final int ACTION_HOME = 1;
    static final int ACTION_SEARCH = 2;
    static final int ACTION_WALLET = 3;
    static final int ACTION_CAMERA = 4;
    static final int ACTION_RECORDER = 5;
    static final int ACTION_FLASHLIGHT = 6;
    static final int ACTION_APP = 7;
    static final int[] ACTION_IDS = {ACTION_NONE, ACTION_HOME, ACTION_SEARCH, ACTION_WALLET,
            ACTION_CAMERA, ACTION_RECORDER, ACTION_FLASHLIGHT, ACTION_APP};
    static final String[] ACTION_NAMES = {"Off", "Google Home controls", "Google search",
            "Google Wallet", "Camera", "Screen recorder", "Flashlight", "Open an app"};

    private static final String SOCKET = "tickerbar-power-v1";
    private static volatile RootPowerGestures active;
    private static final AtomicBoolean policyApplying = new AtomicBoolean();
    private static volatile Boolean lastDoubleEnabled;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private LocalSocket socket;
    private int clicks;
    private final Runnable finishClicks = new Runnable() { public void run() {
        int count;
        synchronized (RootPowerGestures.this) { count = clicks; clicks = 0; }
        // Four/five presses belong entirely to HyperOS emergency handling.
        if (count == 2) trigger(Prefs.n(context, Prefs.POWER_DOUBLE_ACTION),
                Prefs.POWER_DOUBLE_PKG);
        else if (count == 3) trigger(Prefs.n(context, Prefs.POWER_TRIPLE_ACTION),
                Prefs.POWER_TRIPLE_PKG);
    }};

    private RootPowerGestures(Context context) {
        this.context = context.getApplicationContext();
    }

    static void sync(Context context) {
        Context app = context.getApplicationContext();
        boolean doubleOn = Prefs.n(app, Prefs.POWER_DOUBLE_ACTION) != ACTION_NONE;
        boolean tripleOn = Prefs.n(app, Prefs.POWER_TRIPLE_ACTION) != ACTION_NONE;
        updateStockDouble(app, doubleOn);
        if (!RootShell.available() || (!doubleOn && !tripleOn)) {
            stop();
            return;
        }
        start(app);
    }

    private static synchronized void start(Context context) {
        if (active != null || !RootShell.available()) return;
        RootPowerGestures gestures = new RootPowerGestures(context);
        active = gestures;
        new Thread(new Runnable() { public void run() { gestures.run(); }},
                "TickerBar-power-gestures").start();
    }

    static synchronized void stop() {
        RootPowerGestures old = active;
        active = null;
        if (old != null) old.close();
    }

    private void run() {
        try {
            File payload = new File(context.getApplicationInfo().nativeLibraryDir,
                    "libtickerroot.so");
            if (!payload.isFile()) throw new IllegalStateException("root payload missing");
            LocalSocket connected = tryConnect();
            if (connected == null) {
                String command = "pidof tb-power >/dev/null 2>&1 || ("
                        + quote(payload.getAbsolutePath()) + " --power-helper " + Process.myUid()
                        + " >/dev/null 2>&1 &)";
                RootShell.Result launch = RootShell.exec(command);
                if (!launch.connected || launch.code != 0)
                    throw new IllegalStateException("power helper launch failed: " + launch.output);
                for (int i = 0; i < 40 && connected == null && !stopped; i++) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { return; }
                    connected = tryConnect();
                }
            }
            if (connected == null) throw new IllegalStateException("power helper unavailable");
            socket = connected;
            InputStream in = connected.getInputStream();
            int status = ByteBuffer.wrap(readFully(in, 4)).order(ByteOrder.nativeOrder()).getInt();
            if (status != 0) throw new IllegalStateException("power-key observer failed");
            Log.i("TickerBarPower", "Passive root power-button observer ready");
            while (!stopped) {
                int event = in.read();
                if (event < 0) throw new java.io.EOFException();
                if (event == 'P') powerPressed();
            }
        } catch (Throwable failure) {
            if (!stopped) Log.w("TickerBarPower", "Power-button observer ended", failure);
        } finally {
            close();
            synchronized (RootPowerGestures.class) { if (active == this) active = null; }
            // Never retry a native helper in a loop. A settings change or root integration
            // re-apply may start one fresh attempt after the underlying problem is corrected.
        }
    }

    private void powerPressed() {
        synchronized (this) { clicks++; }
        main.removeCallbacks(finishClicks);
        int window = Math.max(250, Math.min(1_500,
                Prefs.n(context, Prefs.POWER_MULTI_MS)));
        main.postDelayed(finishClicks, window);
    }

    private void trigger(int action, String packageKey) {
        if (stopped || !RootShell.available() || action == ACTION_NONE) return;
        if (action == ACTION_HOME) {
            Shell.run("input keyevent KEYCODE_WAKEUP; cmd statusbar click-tile controls", null);
        } else if (action == ACTION_SEARCH) {
            Shell.run("input keyevent KEYCODE_WAKEUP; am start --user 0 -n "
                    + quote(context.getPackageName() + "/.SearchActivity") + " >/dev/null", null);
        } else if (action == ACTION_WALLET) {
            Shell.run("input keyevent KEYCODE_WAKEUP; am start --user 0"
                    + " -a com.google.android.apps.wallet.main.QUICKDRAW"
                    + " -p com.google.android.apps.walletnfcrel >/dev/null", null);
        } else if (action == ACTION_CAMERA) {
            Shell.run("input keyevent KEYCODE_WAKEUP; am start --user 0"
                    + " -a android.media.action.STILL_IMAGE_CAMERA >/dev/null", null);
        } else if (action == ACTION_RECORDER) {
            RootFeatures.openRecorder(context, null);
        } else if (action == ACTION_FLASHLIGHT) {
            Shell.run("cmd statusbar click-tile flashlight", null);
        } else if (action == ACTION_APP) {
            String pkg = Prefs.str(context, packageKey).trim();
            if (!pkg.isEmpty()) Shell.run("input keyevent KEYCODE_WAKEUP; monkey --pct-syskeys 0 -p "
                    + quote(pkg) + " 1 >/dev/null 2>&1", null);
        }
    }

    /** Avoid launching both HyperOS's double-click action and TickerBar's selected action. */
    private static void updateStockDouble(final Context context, final boolean enabled) {
        if (!RootShell.available()) return;
        if (lastDoubleEnabled != null && lastDoubleEnabled.booleanValue() == enabled) return;
        if (!policyApplying.compareAndSet(false, true)) return;
        lastDoubleEnabled = enabled;
        new Thread(new Runnable() { public void run() {
            try {
                if (enabled) {
                    if (!Prefs.get(context).contains(Prefs.POWER_STOCK_DOUBLE)) {
                        String old = Settings.System.getString(context.getContentResolver(),
                                "double_click_power_key");
                        Prefs.put(context, Prefs.POWER_STOCK_DOUBLE, old == null ? "" : old);
                    }
                    RootShell.exec("settings put system double_click_power_key none");
                } else if (Prefs.get(context).contains(Prefs.POWER_STOCK_DOUBLE)) {
                    String old = Prefs.str(context, Prefs.POWER_STOCK_DOUBLE);
                    RootShell.exec(old.isEmpty()
                            ? "settings delete system double_click_power_key"
                            : "settings put system double_click_power_key " + quote(old));
                    Prefs.get(context).edit().remove(Prefs.POWER_STOCK_DOUBLE).apply();
                }
            } finally { policyApplying.set(false); }
        }}, "TickerBar-power-policy").start();
    }

    private LocalSocket tryConnect() {
        try {
            LocalSocket candidate = new LocalSocket();
            candidate.connect(new LocalSocketAddress(SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT));
            return candidate;
        } catch (Exception e) { return null; }
    }

    private synchronized void close() {
        stopped = true;
        main.removeCallbacks(finishClicks);
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    private static byte[] readFully(InputStream in, int length) throws Exception {
        byte[] out = new byte[length];
        int at = 0;
        while (at < length) {
            int n = in.read(out, at, length - at);
            if (n < 0) throw new java.io.EOFException();
            at += n;
        }
        return out;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
