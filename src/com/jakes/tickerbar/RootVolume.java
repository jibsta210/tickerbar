package com.jakes.tickerbar;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Physical volume keys owned by a crash-safe root EVIOCGRAB lease. */
final class RootVolume {
    private static final String SOCKET = "tickerbar-volume-v2";
    private static volatile RootVolume active;

    private final Context context;
    private volatile boolean stopped;
    private LocalSocket socket;
    private final boolean[] consumed = new boolean[2];
    private final long[] downAt = new long[2];
    private long lastRepeat;
    private long ringerBoundaryAt;

    private RootVolume(Context context) { this.context = context.getApplicationContext(); }

    static boolean connected() { return active != null && active.socket != null; }

    static synchronized void start(Context context) {
        if (!RootShell.available() || connected()) return;
        stop();
        RootVolume owner = new RootVolume(context);
        active = owner;
        new Thread(new Runnable() { @Override public void run() { owner.run(); }},
                "TickerBar-root-volume").start();
    }

    static synchronized void stop() {
        RootVolume old = active;
        active = null;
        if (old != null) old.close();
    }

    private void run() {
        try {
            File payload = new File(context.getApplicationInfo().nativeLibraryDir,
                    "libtickerroot.so");
            if (!payload.isFile()) throw new IllegalStateException("root payload missing");
            String command = "pidof tickerbar-vol2 >/dev/null 2>&1 || ("
                    + quote(payload.getAbsolutePath()) + " --volume-helper " + Process.myUid()
                    + " >/dev/null 2>&1 &)";
            RootShell.Result launched = RootShell.exec(command);
            if (!launched.connected || launched.code != 0)
                throw new IllegalStateException("helper launch failed: " + launched.output);

            LocalSocket connected = null;
            for (int i = 0; i < 30 && !stopped; i++) {
                try {
                    connected = new LocalSocket();
                    connected.connect(new LocalSocketAddress(SOCKET,
                            LocalSocketAddress.Namespace.ABSTRACT));
                    break;
                } catch (Exception e) {
                    if (connected != null) try { connected.close(); } catch (Exception ignored) {}
                    connected = null;
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
            }
            if (connected == null) throw new IllegalStateException("helper socket unavailable");
            socket = connected;
            InputStream in = connected.getInputStream();
            OutputStream out = connected.getOutputStream();
            int status = ByteBuffer.wrap(readFully(in, 4)).order(ByteOrder.nativeOrder()).getInt();
            if (status != 0) throw new IllegalStateException("physical key grab failed");
            Log.i("TickerBarVolume", "Root physical-key lease ready");
            RootFeatures.ensureShadeHost(context);

            while (!stopped) {
                ByteBuffer packet = ByteBuffer.wrap(readFully(in, 8)).order(ByteOrder.nativeOrder());
                int code = packet.getInt();
                int value = packet.getInt();
                out.write(handle(code, value) ? 'C' : 'P');
                out.flush();
            }
        } catch (Throwable t) {
            if (!stopped) Log.w("TickerBarVolume", "Root key lease ended", t);
        } finally {
            final boolean retry = !stopped;
            close();
            synchronized (RootVolume.class) { if (active == this) active = null; }
            if (retry) new Thread(new Runnable() { public void run() {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
                RootVolume.start(context);
            }}, "TickerBar-root-volume-retry").start();
        }
    }

    private boolean handle(int linuxCode, int value) {
        int slot = linuxCode == 115 ? 1 : 0;
        int key = linuxCode == 115 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        if (value == 0) {
            boolean result = consumed[slot];
            consumed[slot] = false;
            downAt[slot] = 0;
            return result;
        }
        if (value == 2) {
            if (!consumed[slot]) return false;
            long now = SystemClock.uptimeMillis();
            int hold = Math.max(100, Math.min(2000, Prefs.n(context, Prefs.VOL_HOLD_MS)));
            if (now - downAt[slot] < hold) return true;
            int interval = Math.max(30, Math.min(1000, Prefs.n(context, Prefs.VOL_REPEAT_MS)));
            if (now - lastRepeat >= interval) {
                lastRepeat = now;
                step(key, true);
            }
            return true;
        }
        if (value != 1) return false;
        consumed[slot] = shouldConsume(key);
        downAt[slot] = lastRepeat = SystemClock.uptimeMillis();
        return consumed[slot];
    }

    private boolean shouldConsume(int key) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null || audio.getMode() != AudioManager.MODE_NORMAL
                || remoteVolume(context)) return false;
        boolean media = audio.isMusicActive();
        // Active media owns both volume keys even while DND is on. DND is a notification/
        // ringer policy, not a reason to redirect a playing stream back to the muted ringer.
        if (!media && key == KeyEvent.KEYCODE_VOLUME_UP && Prefs.on(context, Prefs.RINGER_CYCLE)
                && exitDnd(audio)) return true;
        if (!Prefs.on(context, media ? Prefs.VOLUME_ON : Prefs.RINGER_CYCLE)) return false;
        return step(key, false);
    }

    /** With no active media, Volume Up leaves DND before resuming the ringer cycle. */
    private boolean exitDnd(AudioManager audio) {
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getCurrentInterruptionFilter()
                <= NotificationManager.INTERRUPTION_FILTER_ALL) return false;
        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            if (audio.getRingerMode() == AudioManager.RINGER_MODE_SILENT)
                audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
            audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_SAME,
                    AudioManager.FLAG_SHOW_UI);
            return true;
        } catch (SecurityException e) { return false; }
    }

    private boolean step(int key, boolean repeat) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        return audio.isMusicActive() ? stepMedia(audio, key) : stepRinger(audio, key, repeat);
    }

    private boolean stepMedia(AudioManager audio, int key) {
        int old = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int amount = Prefs.n(context, Prefs.VOLUME_STEP);
        if (amount != 1 && amount != 2 && amount != 5 && amount != 10) amount = 5;
        int next = Math.max(audio.getStreamMinVolume(AudioManager.STREAM_MUSIC),
                Math.min(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        old + (key == KeyEvent.KEYCODE_VOLUME_UP ? amount : -amount)));
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI);
            Log.i("TickerBarVolume", "root media " + old + " -> " + next);
            return true;
        } catch (SecurityException e) { return false; }
    }

    private boolean stepRinger(AudioManager audio, int key, boolean repeat) {
        boolean up = key == KeyEvent.KEYCODE_VOLUME_UP;
        NotificationManager nm = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        boolean dnd = nm != null && nm.getCurrentInterruptionFilter()
                > NotificationManager.INTERRUPTION_FILTER_ALL;
        int mode = dnd ? AudioManager.RINGER_MODE_SILENT : audio.getRingerMode();
        int vol = audio.getStreamVolume(AudioManager.STREAM_RING);
        int min = Math.max(1, audio.getStreamMinVolume(AudioManager.STREAM_RING));
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_RING);
        try {
            if (mode == AudioManager.RINGER_MODE_NORMAL && vol > 0
                    && (up ? vol < max : vol > min)) {
                ringerBoundaryAt = 0;
                audio.setStreamVolume(AudioManager.STREAM_RING, vol + (up ? 1 : -1),
                        AudioManager.FLAG_SHOW_UI);
                return true;
            }
            int next;
            if (mode == AudioManager.RINGER_MODE_NORMAL && vol > 0)
                next = up ? -1 : AudioManager.RINGER_MODE_VIBRATE;
            else if (mode == AudioManager.RINGER_MODE_VIBRATE)
                next = up ? AudioManager.RINGER_MODE_NORMAL : AudioManager.RINGER_MODE_SILENT;
            else next = up ? AudioManager.RINGER_MODE_VIBRATE : -1;
            if (next < 0) {
                audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_SAME,
                        AudioManager.FLAG_SHOW_UI);
                return !repeat;
            }
            if (repeat) {
                long now = SystemClock.uptimeMillis();
                int pause = Math.max(0, Math.min(3000, Prefs.n(context, Prefs.RINGER_PAUSE)));
                if (ringerBoundaryAt != 0 && now - ringerBoundaryAt < pause) return true;
            }
            if (dnd && nm != null)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            audio.setRingerMode(next);
            if (next == AudioManager.RINGER_MODE_SILENT && nm != null)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            if (next == AudioManager.RINGER_MODE_NORMAL)
                audio.setStreamVolume(AudioManager.STREAM_RING, min, AudioManager.FLAG_SHOW_UI);
            else audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_SAME,
                    AudioManager.FLAG_SHOW_UI);
            ringerBoundaryAt = SystemClock.uptimeMillis();
            return true;
        } catch (SecurityException e) { return false; }
    }

    /** A cast/remote session owns the physical keys; passive observers must leave DND alone too. */
    static boolean remoteVolume(Context context) {
        MediaSessionManager manager = (MediaSessionManager)
                context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (manager == null) return false;
        try {
            List<MediaController> sessions = manager.getActiveSessions(
                    new ComponentName(context, TickerListener.class));
            for (MediaController controller : sessions) {
                MediaController.PlaybackInfo info = controller.getPlaybackInfo();
                if (info == null || info.getPlaybackType() != MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
                        || info.getVolumeControl() == VolumeProvider.VOLUME_CONTROL_FIXED) continue;
                PlaybackState state = controller.getPlaybackState();
                if (state == null || state.getState() == PlaybackState.STATE_PLAYING) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private synchronized void close() {
        stopped = true;
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
