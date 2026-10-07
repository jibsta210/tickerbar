package com.jakes.tickerbar;

import android.app.PendingIntent;
import android.app.Activity;
import android.app.KeyguardManager;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Updater {

    public static final String REPO = "jibsta210/tickerbar";
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static Intent pendingConfirmation;
    private static java.lang.ref.WeakReference<Activity> foreground = new java.lang.ref.WeakReference<Activity>(null);

    static void foreground(Activity activity) { foreground = new java.lang.ref.WeakReference<Activity>(activity); }
    static void background(Activity activity) {
        if (foreground.get() == activity) foreground.clear();
    }

    static boolean ready(Context ctx) {
        KeyguardManager keyguard = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return ctx instanceof Activity && foreground.get() == ctx && !((Activity) ctx).isFinishing()
                && !((Activity) ctx).isDestroyed() && ((Activity) ctx).hasWindowFocus()
                && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    static void finished() { BUSY.set(false); pendingConfirmation = null; }

    static void confirmWhenVisible(Intent intent) {
        pendingConfirmation = intent;
        Activity activity = foreground.get();
        if (activity != null) resumeConfirmation(activity);
    }

    static boolean resumeConfirmation(Activity activity) {
        if (pendingConfirmation == null || !ready(activity)) return false;
        Intent intent = pendingConfirmation;
        pendingConfirmation = null;
        activity.startActivity(intent);
        return true;
    }

    public interface Cb { void msg(String s); }

    private static void post(final Cb cb, final String s) {
        UI.post(new Runnable() { public void run() { cb.msg(s); } });
    }

    /** Compare "1.2.3" style strings. Returns >0 if a newer than b. */
    static int cmp(String a, String b) {
        String[] x = a.replaceAll("^[vV]", "").split("\\.");
        String[] y = b.replaceAll("^[vV]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parse(x[i]) : 0;
            int yi = i < y.length ? parse(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }
    private static int parse(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    /**
     * Runs when the app opens: checks at most once a minute, and only if auto-update is on.
     * @return true if a check was started
     */
    public static boolean autoCheck(Context ctx, Cb cb) {
        if (!ready(ctx) || BUSY.get() || !Prefs.on(ctx, Prefs.AUTO_UPDATE)) return false;
        long now = System.currentTimeMillis();
        long last = Prefs.get(ctx).getLong(Prefs.LAST_CHECK, 0);
        if (now - last < 60 * 1000L) return false;   // just skips re-checks when bouncing back from a settings screen
        Prefs.get(ctx).edit().putLong(Prefs.LAST_CHECK, now).apply();
        checkAndInstall(ctx, cb);
        return true;
    }

    public static void checkAndInstall(final Context ctx, final Cb cb) {
        if (!BUSY.compareAndSet(false, true)) { post(cb, "Update already in progress"); return; }
        Log.i("TickerBarUpdate", "Checking release; foreground=" + ready(ctx));
        new Thread(new Runnable() { public void run() {
            try {
                String cur = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0).versionName;

                post(cb, "Checking " + REPO + "…");
                JSONObject rel = new JSONObject(get(
                        "https://api.github.com/repos/" + REPO + "/releases/latest"));
                String tag = rel.optString("tag_name", "");
                if (cmp(tag, cur) <= 0) { finished(); post(cb, "Up to date \u00b7 v" + cur); return; }

                String url = null;
                JSONArray assets = rel.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").endsWith(".apk")) {
                        url = a.optString("browser_download_url"); break;
                    }
                }
                if (url == null) { finished(); post(cb, "Release " + tag + " has no APK asset"); return; }

                post(cb, "Downloading " + tag + "\u2026");
                install(ctx, url, cb);
            } catch (Exception e) {
                finished();
                Log.e("TickerBarUpdate", "Update preparation failed", e);
                post(cb, "Couldn't check for updates \u2013 " + e.getClass().getSimpleName());
            }
        }}).start();
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "TickerBar");
        c.setConnectTimeout(15000); c.setReadTimeout(20000);
        InputStream in = c.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close(); c.disconnect();
        return bo.toString("UTF-8");
    }

    private static void install(final Context ctx, String apkUrl, final Cb cb) throws Exception {
        final PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(ctx.getPackageName());
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // HyperOS rejects the silent path with INSTALL_FAILED_ABORTED: Permission denied.
            // Ask for the supported system confirmation on the first attempt.
            sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        final int id = pi.createSession(sp);
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "TickerBar");
            c.setConnectTimeout(15000); c.setReadTimeout(60000);
            try (PackageInstaller.Session session = pi.openSession(id);
                 InputStream in = c.getInputStream();
                 OutputStream out = session.openWrite("tickerbar", 0, -1)) {
                byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                session.fsync(out);
            } finally { c.disconnect(); }
        } catch (Exception e) {
            pi.abandonSession(id);
            throw e;
        }
        UI.post(new Runnable() { public void run() {
            if (!ready(ctx)) {
                pi.abandonSession(id);
                Prefs.get(ctx).edit().remove(Prefs.LAST_CHECK).apply();
                finished();
                cb.msg("Update available — reopen TickerBar to install");
                Log.i("TickerBarUpdate", "Deferred install: app is no longer visible and unlocked");
                return;
            }
            try (PackageInstaller.Session session = pi.openSession(id)) {
                Intent i = new Intent(ctx, InstallReceiver.class);
                PendingIntent p = PendingIntent.getBroadcast(ctx, id, i,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                Prefs.get(ctx).edit().putLong(Prefs.RELAUNCH_AT, System.currentTimeMillis()).commit();
                Log.i("TickerBarUpdate", "Committing session " + id + " with system confirmation");
                session.commit(p.getIntentSender());
                cb.msg("Confirm the update — TickerBar restarts when it is installed");
            } catch (Exception e) {
                pi.abandonSession(id);
                Prefs.get(ctx).edit().remove(Prefs.RELAUNCH_AT).apply();
                finished();
                Log.e("TickerBarUpdate", "Could not start installation", e);
                cb.msg("Could not start update — " + e.getClass().getSimpleName());
            }
        }});
    }
}
