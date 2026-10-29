package com.jakes.tickerbar;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.util.Log;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Passive root observer for ticker/nav gestures and non-consuming Volume Up DND exit. */
final class RootInput {
    private static final String SOCKET = "tickerbar-input-v3";
    private static volatile RootInput active;

    private final Context context;
    private final KeyguardManager keyguard;
    private volatile boolean stopped;
    private volatile int tickerHeight;
    private LocalSocket socket;
    private OutputStream output;

    private RootInput(Context context) {
        this.context = context.getApplicationContext();
        keyguard = (KeyguardManager)this.context.getSystemService(Context.KEYGUARD_SERVICE);
    }

    static boolean connected() { return active != null && active.output != null; }

    static synchronized void start(Context context) {
        // A connection attempt may legitimately take a few seconds while the helper starts.
        // Do not tear that in-flight client down when another lifecycle owner also asks for it.
        if (!RootShell.available() || active != null) return;
        RootInput input = new RootInput(context);
        active = input;
        new Thread(new Runnable() { public void run() { input.run(); }},
                "TickerBar-root-input").start();
    }

    static synchronized void stop() {
        RootInput old = active;
        active = null;
        if (old != null) old.close();
    }

    static void refresh(Context context) {
        RootInput input = active;
        if (input == null) { start(context); return; }
        if (input.output == null) return; // run() sends the latest fields after connecting
        input.sendConfig();
    }

    static void setTickerVisible(Context context, int height) {
        RootInput input = active;
        // The native ticker window is deliberately NOT_TOUCHABLE so SystemUI keeps its
        // normal status-bar gesture stream.  This passive observer is therefore also the
        // ticker's tap target.  Starting it used to return before copying the visible
        // height into the freshly-created client, which sent ticker_on=0 and made every
        // native ticker notification inert.
        if (input == null && height > 0) {
            start(context);
            input = active;
        }
        if (input == null) return;
        input.tickerHeight = Math.max(0, height);
        input.sendConfig();
    }

    private void run() {
        try {
            File payload = new File(context.getApplicationInfo().nativeLibraryDir, "libtickerroot.so");
            if (!payload.isFile()) throw new IllegalStateException("root payload missing");
            LocalSocket connected = tryConnect();
            if (connected == null) {
                String command = "pidof tb-input3 >/dev/null 2>&1 || ("
                        + quote(payload.getAbsolutePath()) + " --input-helper " + Process.myUid()
                        + " >/dev/null 2>&1 &)";
                RootShell.Result launch = RootShell.exec(command);
                if (!launch.connected || launch.code != 0)
                    throw new IllegalStateException("input helper launch failed: " + launch.output);
                for (int i = 0; i < 40 && connected == null && !stopped; i++) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    connected = tryConnect();
                }
            }
            if (connected == null) throw new IllegalStateException("input helper unavailable");
            socket = connected;
            InputStream in = connected.getInputStream();
            int status = ByteBuffer.wrap(readFully(in, 4)).order(ByteOrder.nativeOrder()).getInt();
            if (status != 0) throw new IllegalStateException("touchscreen observer failed");
            output = connected.getOutputStream();
            sendConfig();
            Log.i("TickerBarInput", "Passive root gesture observer ready");
            while (!stopped) {
                int event = in.read();
                if (event < 0) throw new java.io.EOFException();
                if (event == 'W') launchWallet();
                else if (event == 'C') launchCorner();
                else if (event == 'T') NativeTicker.openCurrent();
                else if (event == 'D') NativeTicker.dismissCurrent();
                else if (event == 'N') NativeTicker.pullShade();
                else if (event == 'U') exitManualDnd();
            }
        } catch (Throwable failure) {
            if (!stopped) Log.w("TickerBarInput", "Root gesture observer ended", failure);
        } finally {
            boolean retry = !stopped;
            close();
            synchronized (RootInput.class) { if (active == this) active = null; }
            if (retry) new Thread(new Runnable() { public void run() {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
                RootInput.start(context);
            }}, "TickerBar-root-input-retry").start();
        }
    }

    /** With no active local/cast media, Volume Up leaves DND for the ringer cycle. */
    private void exitManualDnd() {
        if (!Prefs.on(context, Prefs.RINGER_CYCLE)) return;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        // This observer sees Volume Up even when the accessibility volume handler consumes it.
        // Never let its DND shortcut override an actively playing local or cast stream.
        if ((audio != null && audio.isMusicActive()) || RootVolume.remoteVolume(context)) return;
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getCurrentInterruptionFilter()
                <= NotificationManager.INTERRUPTION_FILTER_ALL) return;
        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            Log.i("TickerBarVolume", "Volume Up exited manually-entered DND");
        } catch (SecurityException denied) {
            new Thread(new Runnable() { public void run() {
                RootShell.exec("cmd notification set_dnd off");
            }}, "TickerBar-exit-dnd").start();
        }
    }

    private LocalSocket tryConnect() {
        try {
            LocalSocket candidate = new LocalSocket();
            candidate.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            return candidate;
        } catch (Exception e) { return null; }
    }

    private synchronized void sendConfig() {
        if (output == null || stopped) return;
        try {
            WindowManager wm = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
            Rect bounds = wm == null ? new Rect(0, 0, context.getResources().getDisplayMetrics().widthPixels,
                    context.getResources().getDisplayMetrics().heightPixels)
                    : wm.getCurrentWindowMetrics().getBounds();
            int navId = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            int nav = Prefs.n(context, Prefs.SWIPE_H);
            if (nav <= 0) nav = navId == 0 ? dp(48) : context.getResources().getDimensionPixelSize(navId);
            ByteBuffer config = ByteBuffer.allocate(15 * 4).order(ByteOrder.nativeOrder());
            config.putInt(bounds.width()).putInt(bounds.height()).putInt(nav);
            config.putInt(Prefs.on(context, Prefs.SWIPE_ON) ? 1 : 0);
            config.putInt(Prefs.n(context, Prefs.SWIPE_POS));
            config.putInt(Prefs.n(context, Prefs.SWIPE_TRIGGER));
            config.putInt(Math.max(1, Prefs.n(context, Prefs.SWIPE_DIST)));
            boolean landscapeVideo = bounds.width() > bounds.height()
                    && Prefs.on(context, Prefs.CORNER_HIDE_VIDEO);
            config.putInt(Prefs.on(context, Prefs.CORNER_ON) && !landscapeVideo ? 1 : 0);
            config.putInt(Prefs.n(context, Prefs.CORNER_SIDE));
            config.putInt(Prefs.n(context, Prefs.CORNER_TRIGGER));
            config.putInt(Prefs.n(context, Prefs.CORNER_MS));
            config.putInt(Prefs.n(context, Prefs.CORNER_W));
            config.putInt(ViewConfiguration.get(context).getScaledTouchSlop());
            config.putInt(tickerHeight > 0 ? 1 : 0);
            config.putInt(tickerHeight);
            output.write(config.array());
            output.flush();
        } catch (Exception e) { disconnect(); }
    }

    private void launchWallet() {
        if (!Prefs.on(context, Prefs.SWIPE_ON)) return;
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        if (locked ? !Prefs.on(context, Prefs.CARD_LOCK)
                : !Prefs.on(context, Prefs.CARD_HOME) || !onHome()) return;

        String pkg = Prefs.str(context, Prefs.SWIPE_PKG).trim();
        if (pkg.isEmpty()) pkg = "com.google.android.apps.walletnfcrel";
        Intent intent = null;
        if (Prefs.on(context, Prefs.WALLET_QUICK)
                && "com.google.android.apps.walletnfcrel".equals(pkg)) {
            Intent quick = new Intent("com.google.android.apps.wallet.main.QUICKDRAW").setPackage(pkg);
            if (quick.resolveActivity(context.getPackageManager()) != null) intent = quick;
        }
        if (intent == null) intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (locked) {
            requestUnlockThenOpen(intent, pkg);
            return;
        }
        try { context.startActivity(intent); }
        catch (Exception failure) { launchPackageAsRoot(pkg); }
    }

    private void requestUnlockThenOpen(final Intent target, final String pkg) {
        try {
            context.startActivity(new Intent(context, WalletLaunchActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (Exception ignored) {}
        new Thread(new Runnable() { public void run() {
            long until = android.os.SystemClock.elapsedRealtime() + 15_000L;
            while (!stopped && android.os.SystemClock.elapsedRealtime() < until) {
                if (keyguard == null || !keyguard.isKeyguardLocked()) {
                    try { context.startActivity(target); }
                    catch (Exception failure) { launchPackageAsRoot(pkg); }
                    return;
                }
                try { Thread.sleep(150); } catch (InterruptedException ignored) { return; }
            }
        }}, "TickerBar-wallet-unlock").start();
    }

    private void launchCorner() {
        if (!Prefs.on(context, Prefs.CORNER_ON)) return;
        Intent intent;
        if (Prefs.n(context, Prefs.CORNER_ACTION) == 1) {
            String pkg = Prefs.str(context, Prefs.CORNER_PKG).trim();
            intent = pkg.isEmpty() ? null : context.getPackageManager().getLaunchIntentForPackage(pkg);
        } else intent = SearchActivity.searchIntent(context);
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { context.startActivity(intent); }
        catch (Exception ignored) {}
    }

    private boolean onHome() {
        PackageManager pm = context.getPackageManager();
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        String launcher = home == null || home.activityInfo == null ? "" : home.activityInfo.packageName;
        return !launcher.isEmpty() && launcher.equals(topPackage());
    }

    private String topPackage() {
        UsageStatsManager manager = (UsageStatsManager)context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return "";
        long end = System.currentTimeMillis();
        UsageEvents events = manager.queryEvents(end - 10_000, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String top = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) top = event.getPackageName();
        }
        return top == null ? "" : top;
    }

    private void launchPackageAsRoot(String pkg) {
        final String safe = pkg;
        new Thread(new Runnable() { public void run() {
            RootShell.exec("monkey --pct-syskeys 0 -p " + quote(safe) + " 1 >/dev/null 2>&1");
        }}, "TickerBar-root-launch").start();
    }

    private synchronized void close() {
        stopped = true;
        disconnect();
    }

    private synchronized void disconnect() {
        output = null;
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
