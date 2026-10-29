package com.jakes.tickerbar;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;

/**
 * Root-owned SystemUI overlays. Started with app_process from the authenticated root
 * daemon, so the ticker and wallet sit at the real system-window layer without an
 * AccessibilityService.
 */
public final class RootOverlay {
    private static final String SOCKET = "tickerbar-overlay-v2";
    private static final String PACKAGE = "com.jakes.tickerbar";

    private final int appUid;
    private final Context system;
    private final Context app;
    private final WindowManager windows;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final KeyguardManager keyguard;
    private final PowerManager power;

    private TickerView ticker;
    private WindowManager.LayoutParams tickerLp;
    private String tickerPackage;
    private String tickerKey;
    private boolean tickerAutoCancel;
    private volatile OutputStream callbackOutput;
    private boolean tickerAttached;
    private final ArrayDeque<String[]> tickerQueue = new ArrayDeque<String[]>();
    private final HashSet<String> recalled = new HashSet<String>();
    private int tickerAnimOut, tickerAnimMs;
    private final Runnable hideTicker = new Runnable() { public void run() { hideTickerAnimated(); }};

    private WalletView wallet;
    private boolean walletAttached;
    private boolean walletOn, cardHome, cardLock, walletQuick, cardVisible;
    private int walletPos, walletWidth, walletHeight, walletY, cardOpacity, cardStyle;
    private int cardPeek, walletTrigger, walletDistance;
    private String walletPackage = "com.google.android.apps.walletnfcrel";
    private String cardLabel = "";
    private String launcherPackage = "";

    private RootOverlay(int uid, Context system, Context app) {
        this.appUid = uid;
        this.system = system;
        this.app = app;
        this.windows = (WindowManager) system.getSystemService(Context.WINDOW_SERVICE);
        this.keyguard = (KeyguardManager) system.getSystemService(Context.KEYGUARD_SERVICE);
        this.power = (PowerManager) system.getSystemService(Context.POWER_SERVICE);
        this.launcherPackage = resolveLauncher();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || android.os.Process.myUid() != 0) return;
        int uid = Integer.parseInt(args[0]);
        Context system = systemContext();
        Context app = system.createPackageContext(PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        RootOverlay owner = new RootOverlay(uid, system, app);
        owner.probeWindow();
        owner.listen();
        owner.ui.post(owner.visibilityTick);
        Log.i("TickerBarOverlay", "Root overlay server listening for uid " + uid);
        Looper.loop();
    }

    /** Do not advertise readiness until WindowManager accepted a real system-layer window. */
    private void probeWindow() {
        View probe = new View(app);
        WindowManager.LayoutParams lp = params(1, 1, false);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setTitle("TickerBar root probe");
        windows.addView(probe, lp);
        windows.removeViewImmediate(probe);
    }

    private void listen() throws Exception {
        final LocalServerSocket server = new LocalServerSocket(SOCKET);
        new Thread(new Runnable() { @Override public void run() {
            for (;;) {
                LocalSocket socket = null;
                try {
                    socket = server.accept();
                    Credentials credentials = socket.getPeerCredentials();
                    if (credentials.getUid() != appUid && credentials.getUid() != 0) {
                        socket.close();
                        continue;
                    }
                    OutputStream peer = socket.getOutputStream();
                    peer.write(new byte[]{'O', 'K', '\n'});
                    peer.flush();
                    callbackOutput = peer;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) handle(line);
                } catch (Throwable ignored) {
                } finally {
                    callbackOutput = null;
                    if (socket != null) try { socket.close(); } catch (Exception ignored) {}
                }
            }
        }}, "TickerBar-overlay-socket").start();
    }

    private void handle(String line) {
        final String[] f = line.split("\\t", -1);
        if (f.length == 0) return;
        ui.post(new Runnable() { @Override public void run() {
            try {
                if ("CONFIG".equals(f[0]) && f.length >= 17) configure(f);
                else if ("TICK".equals(f[0]) && f.length >= 23) acceptTicker(f);
                else if ("RECALL".equals(f[0]) && f.length >= 2) recallTicker(text(f[1]));
                else if ("HIDE".equals(f[0])) { tickerQueue.clear(); removeTicker(); }
            } catch (Throwable ignored) {}
        }});
    }

    private void acceptTicker(final String[] fields) {
        String pkg = text(fields[15]);
        int frontMode = integer(fields[22]);
        if (!pkg.isEmpty() && pkg.equals(topPackage())) {
            if (frontMode == 2) return;
            if (frontMode == 1) {
                final String key = text(fields[19]);
                ui.postDelayed(new Runnable() { public void run() {
                    if (!recalled.remove(key)) placeTicker(fields);
                }}, 1200);
                return;
            }
        }
        placeTicker(fields);
    }

    private void placeTicker(String[] fields) {
        if (tickerAttached && bit(fields[21])) {
            tickerQueue.addLast(fields.clone());
            return;
        }
        showTicker(fields);
    }

    private void recallTicker(final String key) {
        if (key == null || key.isEmpty()) return;
        recalled.add(key);
        ui.postDelayed(new Runnable() { public void run() { recalled.remove(key); }}, 2500);
        java.util.Iterator<String[]> iterator = tickerQueue.iterator();
        while (iterator.hasNext()) if (key.equals(text(iterator.next()[19]))) iterator.remove();
        if (tickerAttached && key.equals(tickerKey)) advanceTicker();
    }

    private void configure(String[] f) {
        walletOn = bit(f[1]); cardHome = bit(f[2]); cardLock = bit(f[3]);
        walletQuick = bit(f[4]); cardVisible = bit(f[5]);
        walletPos = integer(f[6]); walletWidth = integer(f[7]);
        walletHeight = integer(f[8]); walletY = integer(f[9]);
        cardOpacity = integer(f[10]); cardStyle = integer(f[11]);
        walletPackage = text(f[12]); cardLabel = text(f[13]);
        cardPeek = integer(f[14]); walletTrigger = integer(f[15]);
        walletDistance = integer(f[16]);
        updateWallet();
    }

    private void showTicker(String[] f) {
        boolean enabled = bit(f[1]);
        if (!enabled || (keyguard != null && keyguard.isKeyguardLocked())) return;
        int style = integer(f[2]), lines = integer(f[3]), height = integer(f[4]);
        int textSp = integer(f[5]), opacity = integer(f[6]), dwell = integer(f[7]);
        boolean autoPad = bit(f[8]), hop = bit(f[9]);
        int pad = integer(f[10]), gap = integer(f[11]);
        String appName = text(f[12]), title = text(f[13]), body = text(f[14]);
        tickerPackage = f.length > 15 ? text(f[15]) : "";
        int animIn = integer(f[16]);
        tickerAnimOut = integer(f[17]);
        tickerAnimMs = Math.max(0, integer(f[18]));
        tickerKey = text(f[19]);
        tickerAutoCancel = bit(f[20]);

        if (ticker == null) {
            ticker = new TickerView(app);
            ticker.setOnTouchListener(new TickerTouch());
        }
        readTickerGeometry();
        float textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp,
                app.getResources().getDisplayMetrics());
        ticker.configure(autoPad, pad, hop, gap, Prefs.n(app, Prefs.SCROLL_MODE),
                Prefs.n(app, Prefs.SPEED), Prefs.n(app, Prefs.DELAY), textPx, opacity);
        Palette pal = Palette.of(app);
        ticker.setLook(style, pal.surfaceHigh, pal.onSurface, pal.onSurfaceVariant, pal.primary);
        String[] tidy = ShadeService.tidy(appName, title, body);
        int count = Math.max(1, Math.min(style == TickerView.LOOK_CARD ? 2 : 3, lines));
        String[] bold = null, values;
        int[] styles;
        if (style == TickerView.LOOK_CARD) {
            if (count == 1) {
                bold = new String[]{tidy[1]}; values = new String[]{tidy[2]};
                styles = new int[]{TickerView.STYLE_NORMAL};
            } else {
                bold = new String[]{tidy[1], ""}; values = new String[]{tidy[0], tidy[2]};
                styles = new int[]{TickerView.STYLE_MUTED, TickerView.STYLE_NORMAL};
            }
        } else if (count == 1) {
            values = new String[]{tidy[2].isEmpty() ? tidy[1] : tidy[1] + "  —  " + tidy[2]};
            styles = new int[]{TickerView.STYLE_NORMAL};
        } else if (count == 2) {
            values = new String[]{tidy[1], tidy[2]};
            styles = new int[]{TickerView.STYLE_BOLD, TickerView.STYLE_NORMAL};
        } else {
            values = new String[]{tidy[0], tidy[1], tidy[2]};
            styles = new int[]{TickerView.STYLE_MUTED, TickerView.STYLE_BOLD, TickerView.STYLE_NORMAL};
        }
        Drawable icon = null;
        if (style == TickerView.LOOK_CARD && tickerPackage != null && !tickerPackage.isEmpty()) {
            try { icon = app.getPackageManager().getApplicationIcon(tickerPackage).mutate(); }
            catch (Exception ignored) {}
        }
        ticker.setContent(bold, values, styles, icon, false, 0);
        int bar = height > 0 ? height : Math.max(statusBarHeight(),
                (int)Math.ceil(TickerView.lineHeight(textPx) * (count + 0.3f)));
        tickerLp = params(WindowManager.LayoutParams.MATCH_PARENT, bar, true);
        tickerLp.gravity = Gravity.TOP | Gravity.START;
        tickerLp.setTitle("TickerBar root ticker");
        try {
            boolean fresh = !tickerAttached;
            if (!fresh) windows.updateViewLayout(ticker, tickerLp);
            else { windows.addView(ticker, tickerLp); tickerAttached = true; }
            ticker.setVisibility(View.VISIBLE);
            ticker.animate().cancel();
            if (fresh) animateTickerIn(animIn, tickerAnimMs, bar);
            ui.removeCallbacks(hideTicker);
            ui.postDelayed(hideTicker, Math.max(800, dwell));
        } catch (Throwable failure) {
            Log.e("TickerBarOverlay", "Could not add root ticker window", failure);
            removeTicker();
        }
    }

    private final class TickerTouch implements View.OnTouchListener {
        float x, y;
        public boolean onTouch(View view, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                x = event.getRawX(); y = event.getRawY(); return true;
            }
            if (event.getActionMasked() != MotionEvent.ACTION_UP) return true;
            float dx = event.getRawX() - x, dy = event.getRawY() - y;
            int slop = ViewConfiguration.get(app).getScaledTouchSlop();
            if (dy > slop && dy > Math.abs(dx)) {
                advanceTicker();
                runCommand("/system/bin/cmd", "statusbar", "expand-notifications");
            } else if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                String fallback = tickerPackage;
                boolean sent = sendClick();
                advanceTicker();
                if (!sent) launch(fallback, false);
            } else {
                advanceTicker();
            }
            return true;
        }
    }

    private synchronized boolean sendClick() {
        OutputStream out = callbackOutput;
        if (out == null || tickerKey == null || tickerKey.isEmpty()) return false;
        String event = "CLICK\t" + encoded(tickerKey) + "\t" + encoded(tickerPackage)
                + "\t" + (tickerAutoCancel ? "1" : "0") + "\n";
        try {
            out.write(event.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (Exception e) { return false; }
    }

    private void removeTicker() {
        ui.removeCallbacks(hideTicker);
        if (ticker != null) ticker.animate().cancel();
        if (tickerAttached && ticker != null) try { windows.removeViewImmediate(ticker); } catch (Throwable ignored) {}
        tickerAttached = false;
    }

    private void advanceTicker() {
        removeTicker();
        String[] next = tickerQueue.pollFirst();
        if (next != null) showTicker(next);
    }

    private void animateTickerIn(int mode, int duration, int height) {
        ticker.setAlpha(1f);
        ticker.setTranslationX(0f);
        ticker.setTranslationY(0f);
        ticker.setRotationX(0f);
        ticker.setCameraDistance(dp(8000));
        if (mode == 1) ticker.setAlpha(0f);
        else if (mode == 2) ticker.setTranslationY(-height);
        else if (mode == 3)
            ticker.setTranslationX(app.getResources().getDisplayMetrics().widthPixels);
        else if (mode == 4 || mode == 5) {
            ticker.setPivotY(mode == 5 ? 0f : height / 2f);
            ticker.setRotationX(90f);
        }
        ticker.animate().alpha(1f).translationX(0f).translationY(0f).rotationX(0f)
                .setDuration(duration).start();
    }

    private void hideTickerAnimated() {
        if (!tickerAttached || ticker == null || tickerAnimMs <= 0 || tickerAnimOut == 0) {
            advanceTicker();
            return;
        }
        ticker.animate().cancel();
        android.view.ViewPropertyAnimator animation = ticker.animate().setDuration(tickerAnimMs)
                .withEndAction(new Runnable() { public void run() { advanceTicker(); }});
        if (tickerAnimOut == 1) animation.alpha(0f);
        else if (tickerAnimOut == 2) animation.translationY(-ticker.getHeight());
        else if (tickerAnimOut == 3)
            animation.translationX(-app.getResources().getDisplayMetrics().widthPixels);
        else if (tickerAnimOut == 4 || tickerAnimOut == 5) {
            ticker.setPivotY(tickerAnimOut == 5 ? 0f : ticker.getHeight() / 2f);
            animation.rotationX(-90f);
        }
        animation.start();
    }

    private final Runnable visibilityTick = new Runnable() { public void run() {
        updateWallet();
        ui.postDelayed(this, 1000);
    }};

    private void updateWallet() {
        boolean locked = keyguard != null && keyguard.isKeyguardLocked();
        boolean home = launcherPackage.equals(topPackage());
        boolean want = walletOn && (power == null || power.isInteractive())
                && (locked ? cardLock : cardHome && home);
        if (!want) {
            if (walletAttached && wallet != null) try { windows.removeViewImmediate(wallet); } catch (Throwable ignored) {}
            walletAttached = false;
            return;
        }
        if (wallet == null) wallet = new WalletView(app);
        int screen = app.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(40), screen * Math.max(5, Math.min(100, walletWidth)) / 100);
        int height = walletHeight > 0 ? walletHeight : Math.max(dp(24), navigationBarHeight());
        WindowManager.LayoutParams lp = params(width, height, true);
        lp.gravity = Gravity.BOTTOM | (walletPos == 0 ? Gravity.LEFT
                : walletPos == 2 ? Gravity.RIGHT : Gravity.CENTER_HORIZONTAL);
        lp.y = Math.max(0, walletY);
        lp.setTitle("TickerBar root wallet");
        try {
            if (walletAttached) windows.updateViewLayout(wallet, lp);
            else { windows.addView(wallet, lp); walletAttached = true; }
            wallet.invalidate();
        } catch (Throwable failure) {
            Log.e("TickerBarOverlay", "Could not add root wallet window", failure);
            walletAttached = false;
        }
    }

    private final class WalletView extends View {
        final CardArt art;
        final RectF card = new RectF();
        float downX, downY, lastY;
        boolean moved, fired;
        WalletView(Context context) { super(context); art = new CardArt(context); }
        @Override protected void onDraw(Canvas canvas) {
            if (!cardVisible) return;
            float width = getWidth() * 0.96f, height = width * 0.63f;
            float peek = cardPeek > 0 ? Math.min(cardPeek, getHeight()) : getHeight() * 0.45f;
            if (walletY < 0) peek += walletY;
            peek = Math.max(dp(3), peek);
            card.set((getWidth() - width) / 2f, getHeight() - peek,
                    (getWidth() + width) / 2f, getHeight() - peek + height);
            art.labelSize = Math.min(height * 0.085f, Math.max(dp(9), peek * 0.45f));
            art.labelBaseline = Math.min(height * 0.165f, peek / 2f + art.labelSize * 0.36f);
            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            art.draw(canvas, card, Math.max(5, Math.min(100, cardOpacity)) / 100f,
                    cardStyle, cardLabel);
            canvas.restore();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = lastY = event.getRawY();
                moved = fired = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                lastY = event.getRawY();
                int slop = ViewConfiguration.get(app).getScaledTouchSlop();
                if (Math.abs(event.getRawX() - downX) > slop
                        || Math.abs(lastY - downY) > slop) moved = true;
                if (!fired && walletTrigger != 1
                        && downY - lastY >= Math.max(1, walletDistance)) {
                    fired = true;
                    launch(walletPackage, walletQuick);
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float rise = downY - event.getRawY();
                if (!fired && walletTrigger != 1 && rise >= Math.max(1, walletDistance)) {
                    fired = true;
                    launch(walletPackage, walletQuick);
                } else if (!fired && walletTrigger != 2 && !moved) {
                    fired = true;
                    launch(walletPackage, walletQuick);
                } else if (!fired && !moved) {
                    passThrough(event.getRawX(), event.getRawY());
                }
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL
                    && !fired && walletTrigger != 1 && downY - lastY > 0) {
                fired = true;
                launch(walletPackage, walletQuick);
            }
            return true;
        }
    }

    private void passThrough(final float x, final float y) {
        if (!walletAttached || wallet == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams)wallet.getLayoutParams();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { windows.updateViewLayout(wallet, lp); } catch (Throwable ignored) {}
        new Thread(new Runnable() { public void run() {
            try { Thread.sleep(45); } catch (InterruptedException ignored) {}
            runCommand("/system/bin/input", "tap", Integer.toString((int)x), Integer.toString((int)y));
            ui.postDelayed(new Runnable() { public void run() { updateWallet(); }}, 80);
        }}, "TickerBar-wallet-pass").start();
    }

    private void launch(String pkg, boolean quick) {
        if (pkg == null || pkg.isEmpty()) return;
        PackageManager pm = app.getPackageManager();
        Intent intent = null;
        if (quick && "com.google.android.apps.walletnfcrel".equals(pkg)) {
            Intent candidate = new Intent("com.google.android.apps.wallet.main.QUICKDRAW").setPackage(pkg);
            if (candidate.resolveActivity(pm) != null) intent = candidate;
        }
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { system.startActivity(intent); } catch (Throwable ignored) {}
    }

    private WindowManager.LayoutParams params(int width, int height, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR, flags, PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        return lp;
    }

    private String resolveLauncher() {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo ri = app.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null ? "" : ri.activityInfo.packageName;
    }

    private String topPackage() {
        try {
            ActivityManager am = (ActivityManager)system.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
            return tasks.isEmpty() || tasks.get(0).topActivity == null ? ""
                    : tasks.get(0).topActivity.getPackageName();
        } catch (Throwable ignored) { return ""; }
    }

    private int statusBarHeight() {
        int id = app.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int height = id == 0 ? dp(32) : app.getResources().getDimensionPixelSize(id);
        try {
            Display display = windows.getDefaultDisplay();
            DisplayCutout cutout = display == null ? null : display.getCutout();
            if (cutout != null) for (Rect rect : cutout.getBoundingRects())
                if (rect.top <= 0) height = Math.max(height, rect.bottom);
        } catch (Throwable ignored) {}
        return height;
    }

    private void readTickerGeometry() {
        try {
            Display display = windows.getDefaultDisplay();
            DisplayCutout cutout = display == null ? null : display.getCutout();
            java.util.ArrayList<Rect> bounds = new java.util.ArrayList<Rect>();
            if (cutout != null) for (Rect rect : cutout.getBoundingRects())
                if (!rect.isEmpty()) bounds.add(new Rect(rect));
            ticker.setCutouts(bounds);
            if (display != null && Build.VERSION.SDK_INT >= 31) {
                RoundedCorner left = display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
                RoundedCorner right = display.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
                ticker.setCorners(left == null ? 0 : left.getRadius(),
                        left == null ? 0 : left.getCenter().x,
                        left == null ? 0 : left.getCenter().y,
                        right == null ? 0 : right.getRadius(),
                        right == null ? 0 : right.getCenter().x,
                        right == null ? 0 : right.getCenter().y);
            }
        } catch (Throwable ignored) {}
    }

    private int navigationBarHeight() {
        int id = app.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id == 0 ? dp(48) : app.getResources().getDimensionPixelSize(id);
    }

    private int dp(float value) { return Math.round(value * app.getResources().getDisplayMetrics().density); }
    private static int integer(String value) { try { return Integer.parseInt(value); } catch (Exception e) { return 0; }}
    private static boolean bit(String value) { return "1".equals(value); }
    private static String text(String value) {
        try { return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
    private static String encoded(String value) {
        if (value == null) value = "";
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
    private static void runCommand(String... args) {
        try { new ProcessBuilder(args).redirectErrorStream(true).start(); } catch (Exception ignored) {}
    }
    private static Context systemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method systemMain = activityThread.getDeclaredMethod("systemMain");
        systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        java.lang.reflect.Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context)getSystemContext.invoke(thread);
    }
}
