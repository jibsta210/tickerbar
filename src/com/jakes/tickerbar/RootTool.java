package com.jakes.tickerbar;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.net.TetheringManager;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Small entry point run by app_process through the UID-authenticated root daemon. */
public final class RootTool {
    private RootTool() {}

    public static void main(String[] args) {
        try {
            Context context = systemContext();
            if (args.length == 3 && "torch".equals(args[0])) {
                setTorch(context, args[1], args[2]);
                return;
            }
            if (args.length != 2 || !"hotspot".equals(args[0])) fail("usage", 2);
            TetheringManager tm = (TetheringManager) context.getSystemService("tethering");
            if (tm == null) fail("tethering service unavailable", 3);
            if ("status".equals(args[1])) {
                System.out.println(hotspotOn(tm) ? "on" : "off");
                return;
            }
            if ("off".equals(args[1])) {
                tm.stopTethering(TetheringManager.TETHERING_WIFI);
                System.out.println("ok");
                return;
            }
            if (!"on".equals(args[1])) fail("unknown action", 2);

            final CountDownLatch done = new CountDownLatch(1);
            final int[] error = {-1};
            Executor direct = new Executor() { public void execute(Runnable r) { r.run(); } };
            tm.startTethering(TetheringManager.TETHERING_WIFI, direct,
                    new TetheringManager.StartTetheringCallback() {
                        @Override public void onTetheringStarted() { error[0] = 0; done.countDown(); }
                        @Override public void onTetheringFailed(int e) { error[0] = e; done.countDown(); }
                    });
            if (!done.await(15, TimeUnit.SECONDS)) fail("tethering request timed out", 4);
            if (error[0] != 0) fail("tethering failed: " + error[0], 5);
            System.out.println("ok");
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(1);
        }
    }

    /** Camera permission belongs to Android's root process here, not to the UI app. */
    private static void setTorch(Context context, String cameraId, String action) throws Exception {
        if (!"on".equals(action) && !"off".equals(action)) fail("unknown action", 2);
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) fail("camera service unavailable", 3);
        cm.setTorchMode(cameraId, "on".equals(action));
        System.out.println("ok");
    }

    private static Context systemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    private static boolean hotspotOn(TetheringManager tm) {
        String[] interfaces = tm.getTetheredIfaces();
        if (interfaces == null) return false;
        for (String iface : interfaces)
            if (iface != null && (iface.startsWith("wlan") || iface.startsWith("softap")
                    || iface.startsWith("ap_br_wlan") || iface.startsWith("ap_br_softap")))
                return true;
        return false;
    }

    private static void fail(String message, int code) {
        System.err.println(message);
        System.exit(code);
    }
}
