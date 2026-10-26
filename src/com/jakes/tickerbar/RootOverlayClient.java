package com.jakes.tickerbar;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Persistent authenticated channel to the root-owned ticker and wallet windows. */
final class RootOverlayClient {
    private static final String SOCKET = "tickerbar-overlay-v2";
    // HyperOS 3 rejects a standalone uid-0 app_process as an unknown
    // WindowManager client. Keep the proven Accessibility overlay fallback
    // until this helper is hosted by an AMS-registered process.
    private static final boolean ENABLED = false;
    private static volatile RootOverlayClient active;

    private final Context context;
    private LocalSocket socket;
    private OutputStream output;
    private volatile boolean stopped;

    private RootOverlayClient(Context context) { this.context = context.getApplicationContext(); }

    static boolean connected() { return active != null && active.output != null; }

    static synchronized void start(Context context) {
        if (!ENABLED) return;
        if (!RootShell.available() || connected()) return;
        stop();
        RootOverlayClient client = new RootOverlayClient(context);
        active = client;
        new Thread(new Runnable() { public void run() { client.connect(); }},
                "TickerBar-root-overlay").start();
    }

    static synchronized void stop() {
        RootOverlayClient old = active;
        active = null;
        if (old != null) old.close();
    }

    static boolean post(Item item) {
        RootOverlayClient client = active;
        if (client == null || client.output == null || item == null) return false;
        String line = "TICK\t" + bit(Prefs.on(client.context, Prefs.TICKER_ON))
                + "\t" + Prefs.n(client.context, Prefs.STYLE)
                + "\t" + Prefs.n(client.context, Prefs.LINES)
                + "\t" + Prefs.n(client.context, Prefs.HEIGHT)
                + "\t" + Prefs.n(client.context, Prefs.TEXT)
                + "\t" + Prefs.n(client.context, Prefs.OPACITY)
                + "\t" + Prefs.n(client.context, Prefs.DWELL)
                + "\t" + bit(Prefs.on(client.context, Prefs.AUTO_PAD))
                + "\t" + bit(Prefs.on(client.context, Prefs.HOP))
                + "\t" + Prefs.n(client.context, Prefs.PAD)
                + "\t" + Prefs.n(client.context, Prefs.HOP_GAP)
                + "\t" + text(item.app) + "\t" + text(item.title) + "\t" + text(item.body)
                + "\t" + text(item.pkg)
                + "\t" + Prefs.n(client.context, Prefs.ANIM_IN)
                + "\t" + Prefs.n(client.context, Prefs.ANIM_OUT)
                + "\t" + Prefs.n(client.context, Prefs.ANIM_MS)
                + "\t" + text(item.key) + "\t" + bit(item.autoCancel)
                + "\t" + bit(Prefs.on(client.context, Prefs.QUEUE))
                + "\t" + Prefs.n(client.context, Prefs.FRONT_MODE) + "\n";
        return client.send(line);
    }

    static void recall(String key) {
        RootOverlayClient client = active;
        if (client != null && client.output != null && key != null)
            client.send("RECALL\t" + text(key) + "\n");
    }

    static void refresh(Context context) {
        RootOverlayClient client = active;
        if (client == null || client.output == null) { start(context); return; }
        client.sendConfig();
    }

    private void connect() {
        try {
            LocalSocket connected = tryConnect();
            if (connected == null) {
                String apk = context.getApplicationInfo().sourceDir;
                String command = "CLASSPATH=" + quote(apk)
                        + " /system/bin/app_process /system/bin com.jakes.tickerbar.RootOverlay "
                        + Process.myUid() + " >/dev/null 2>&1 &";
                RootShell.Result launch = RootShell.exec(command);
                if (!launch.connected || launch.code != 0)
                    throw new IllegalStateException("overlay launch failed: " + launch.output);
                for (int i = 0; i < 40 && connected == null && !stopped; i++) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    connected = tryConnect();
                }
            }
            if (connected == null) throw new IllegalStateException("overlay socket unavailable");
            socket = connected;
            byte[] hello = new byte[3];
            int at = 0;
            while (at < hello.length) {
                int n = connected.getInputStream().read(hello, at, hello.length - at);
                if (n < 0) throw new java.io.EOFException();
                at += n;
            }
            if (hello[0] != 'O' || hello[1] != 'K') throw new IllegalStateException("overlay rejected");
            output = connected.getOutputStream();
            new Thread(new Runnable() { public void run() { readEvents(); }},
                    "TickerBar-root-overlay-events").start();
            sendConfig();
            Log.i("TickerBarOverlay", "Root system-window service ready");
            ShadeService.refresh();
            RootFeatures.detachAccessibilityIfReady(context);
        } catch (Throwable t) {
            if (!stopped) Log.w("TickerBarOverlay", "Root overlay unavailable", t);
            close();
            synchronized (RootOverlayClient.class) { if (active == this) active = null; }
        }
    }

    private LocalSocket tryConnect() {
        try {
            LocalSocket candidate = new LocalSocket();
            candidate.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            return candidate;
        } catch (Exception e) { return null; }
    }

    private void readEvents() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (!stopped && (line = reader.readLine()) != null) {
                String[] fields = line.split("\\t", -1);
                if (fields.length >= 4 && "CLICK".equals(fields[0]))
                    TickerListener.open(context, decode(fields[1]), decode(fields[2]),
                            "1".equals(fields[3]));
            }
        } catch (Exception ignored) {
        }
        reconnect();
    }

    private void sendConfig() {
        String line = "CONFIG\t" + bit(Prefs.on(context, Prefs.SWIPE_ON))
                + "\t" + bit(Prefs.on(context, Prefs.CARD_HOME))
                + "\t" + bit(Prefs.on(context, Prefs.CARD_LOCK))
                + "\t" + bit(Prefs.on(context, Prefs.WALLET_QUICK))
                + "\t" + bit(Prefs.on(context, Prefs.SWIPE_PILL))
                + "\t" + Prefs.n(context, Prefs.SWIPE_POS)
                + "\t" + Prefs.n(context, Prefs.SWIPE_W)
                + "\t" + Prefs.n(context, Prefs.SWIPE_H)
                + "\t" + Prefs.n(context, Prefs.SWIPE_Y)
                + "\t" + Prefs.n(context, Prefs.CARD_OPACITY)
                + "\t" + Prefs.n(context, Prefs.CARD_STYLE)
                + "\t" + text(Prefs.str(context, Prefs.SWIPE_PKG))
                + "\t" + text(Prefs.str(context, Prefs.CARD_LABEL))
                + "\t" + Prefs.n(context, Prefs.CARD_PEEK)
                + "\t" + Prefs.n(context, Prefs.SWIPE_TRIGGER)
                + "\t" + Prefs.n(context, Prefs.SWIPE_DIST) + "\n";
        send(line);
    }

    private synchronized boolean send(String line) {
        if (output == null || stopped) return false;
        try {
            output.write(line.getBytes(StandardCharsets.UTF_8));
            output.flush();
            return true;
        } catch (Exception e) {
            reconnect();
            return false;
        }
    }

    private void reconnect() {
        boolean retry;
        synchronized (RootOverlayClient.class) {
            retry = !stopped && active == this;
            if (retry) active = null;
        }
        if (!retry) return;
        close();
        new Thread(new Runnable() { public void run() {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { return; }
            RootOverlayClient.start(context);
        }}, "TickerBar-root-overlay-retry").start();
    }

    private synchronized void close() {
        stopped = true;
        output = null;
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    private static String bit(boolean value) { return value ? "1" : "0"; }
    private static String text(String value) {
        if (value == null) value = "";
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
    private static String decode(String value) {
        try { return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
