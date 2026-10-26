package com.jakes.tickerbar;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Direct client for Tickerbar's UID-authenticated local root worker. */
final class RootShell {
    static final String SOCKET = "tickerbar-root";

    static final class Result {
        final boolean connected;
        final int code;
        final String output;
        Result(boolean connected, int code, String output) {
            this.connected = connected;
            this.code = code;
            this.output = output == null ? "" : output;
        }
    }

    /** A live connection whose close restores the stock system-shade behavior. */
    static final class StatusBarLease implements java.io.Closeable {
        private LocalSocket socket;
        StatusBarLease(LocalSocket socket) { this.socket = socket; }
        @Override public synchronized void close() {
            if (socket == null) return;
            try { socket.close(); } catch (Throwable ignored) {}
            socket = null;
        }
    }

    private RootShell() {}

    static Result exec(String command) {
        // The exploit creates the daemon in the kernel SELinux domain. That domain has
        // real uid 0 and can access files, but Android's service manager deliberately
        // exposes no Binder services to it. Enter the ordinary shell domain for each
        // command while retaining uid 0; this makes `cmd`, `content`, and `service`
        // work without weakening the daemon's UID-authenticated socket boundary.
        String runnable = shellDomain(command);
        Result result = transact(runnable, 'R', false);
        // A daemon started by v1.14.17 or earlier only understands the original
        // output-only command mode. Keep root alive across an in-place app update;
        // the next boot starts the status-aware daemon bundled with this APK.
        if (!result.connected)
            return transact(runnable, 'C', true);
        return result;
    }

    private static String shellDomain(String command) {
        return "exec /system/bin/runcon u:r:shell:s0 /system/bin/sh -c " + quote(command);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static Result transact(String command, int mode, boolean logFailure) {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(30_000);
            byte[] cmd = command.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(mode);
            out.write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                    .putInt(cmd.length).array());
            out.write(cmd);
            out.flush();
            socket.shutdownOutput();

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0)
                captured.write(buf, 0, n);
            byte[] response = captured.toByteArray();
            if (mode == 'C')
                return new Result(true, 0, new String(response, StandardCharsets.UTF_8));
            if (response.length < 4)
                return new Result(false, -1, "Root daemon returned an incomplete response");
            int status = ByteBuffer.wrap(response, response.length - 4, 4)
                    .order(ByteOrder.nativeOrder()).getInt();
            return new Result(true, status,
                    new String(response, 0, response.length - 4, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            if (logFailure) Log.w("TickerBarRoot", "Root socket unavailable: " + t);
            return new Result(false, -1, t.toString());
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    static boolean available() {
        Result r = exec("id");
        boolean ok = r.connected && r.code == 0 && r.output.contains("uid=0");
        if (!ok && r.connected) Log.w("TickerBarRoot", "Root probe: " + r.output.trim());
        return ok;
    }

    /** New daemons acknowledge this mode after disabling HyperOS's shade. */
    static StatusBarLease acquireStatusBarLease() {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(8_000);
            OutputStream out = socket.getOutputStream();
            out.write('S');
            out.flush();
            byte[] raw = new byte[4];
            InputStream in = socket.getInputStream();
            int at = 0;
            while (at < raw.length) {
                int n = in.read(raw, at, raw.length - at);
                if (n < 0) throw new java.io.EOFException("daemon does not support shade lease");
                at += n;
            }
            int status = ByteBuffer.wrap(raw).order(ByteOrder.nativeOrder()).getInt();
            if (status != 0) throw new java.io.IOException("statusbar command exited " + status);
            socket.setSoTimeout(0);
            return new StatusBarLease(socket);
        } catch (Throwable t) {
            try { socket.close(); } catch (Throwable ignored) {}
            Log.i("TickerBarRoot", "Native shade suppression unavailable: " + t);
            return null;
        }
    }
}
