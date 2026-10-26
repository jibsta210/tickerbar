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

    private RootShell() {}

    static Result exec(String command) {
        Result result = transact(command, 'R', false);
        // A daemon started by v1.14.17 or earlier only understands the original
        // output-only command mode. Keep root alive across an in-place app update;
        // the next boot starts the status-aware daemon bundled with this APK.
        if (!result.connected)
            return transact(command, 'C', true);
        return result;
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
}
