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
    static final String SOCKET = "/data/local/tmp/tickerbar-root.sock";

    static final class Result {
        final boolean connected;
        final String output;
        Result(boolean connected, String output) {
            this.connected = connected;
            this.output = output == null ? "" : output;
        }
    }

    private RootShell() {}

    static Result exec(String command) {
        LocalSocket socket = new LocalSocket();
        try {
            socket.setSoTimeout(30_000);
            socket.connect(new LocalSocketAddress(SOCKET,
                    LocalSocketAddress.Namespace.FILESYSTEM));
            byte[] cmd = command.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write('C');
            out.write(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder())
                    .putInt(cmd.length).array());
            out.write(cmd);
            out.flush();
            socket.shutdownOutput();

            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (captured.size() < 64 * 1024)
                    captured.write(buf, 0, Math.min(n, 64 * 1024 - captured.size()));
            }
            return new Result(true, captured.toString(StandardCharsets.UTF_8.name()));
        } catch (Throwable t) {
            return new Result(false, t.toString());
        } finally {
            try { socket.close(); } catch (Throwable ignored) {}
        }
    }

    static boolean available() {
        Result r = exec("id");
        boolean ok = r.connected && r.output.contains("uid=0");
        if (!ok && r.connected) Log.w("TickerBarRoot", "Root probe: " + r.output.trim());
        return ok;
    }
}
