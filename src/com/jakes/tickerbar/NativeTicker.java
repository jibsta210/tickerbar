package com.jakes.tickerbar;

import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.RoundedCorner;
import android.view.WindowManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Root-authorized app overlay for the ticker; no AccessibilityService is involved. */
final class NativeTicker {
    private static NativeTicker active;

    private final Context context;
    private final WindowManager windows;
    private final KeyguardManager keyguard;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Item> queue = new ArrayDeque<Item>();
    private TickerView view;
    private WindowManager.LayoutParams layout;
    private Item current;
    private boolean attached;
    private int animOut;
    private int animMs;
    /** Serializes SystemUI icon leases so a fast replacement cannot restore an older ticker. */
    private final ExecutorService statusCommands = Executors.newSingleThreadExecutor();
    private RootShell.StatusBarLease statusIconLease;       // accessed only by statusCommands
    private String fallbackIconToken;                       // accessed only by statusCommands
    private boolean nativeIconLeaseUnsupported;             // old root daemons use the fallback
    private static final String ICON_OWNER = "/data/local/tmp/tickerbar-status-icons";

    private NativeTicker(Context context) {
        this.context = context.getApplicationContext();
        windows = (WindowManager)this.context.getSystemService(Context.WINDOW_SERVICE);
        keyguard = (KeyguardManager)this.context.getSystemService(Context.KEYGUARD_SERVICE);
    }

    static synchronized void start(Context context) {
        if (!RootShell.available() || active != null) return;
        if (!Settings.canDrawOverlays(context)) {
            Log.w("TickerBarNative", "Root overlay app-op has not reached the app yet");
            return;
        }
        active = new NativeTicker(context);
        Log.i("TickerBarNative", "Native ticker ready");
    }

    static synchronized void stop() {
        NativeTicker old = active;
        active = null;
        if (old != null) old.ui.post(new Runnable() { public void run() {
            old.remove();
            old.statusCommands.shutdown();
        }});
    }

    static boolean ready() { return active != null; }

    static void refresh(Context context) {
        NativeTicker ticker = active;
        if (ticker == null) { start(context); return; }
        ticker.ui.post(new Runnable() { public void run() {
            if (!Prefs.on(ticker.context, Prefs.TICKER_ON)) ticker.remove();
            else if (ticker.attached && ticker.current != null) ticker.show(ticker.current);
        }});
    }

    static boolean post(final Item item) {
        final NativeTicker ticker = active;
        if (ticker == null || item == null) return false;
        ticker.ui.post(new Runnable() { public void run() { ticker.accept(item); }});
        return true;
    }

    static void recall(final String key) {
        final NativeTicker ticker = active;
        if (ticker == null || key == null) return;
        ticker.ui.post(new Runnable() { public void run() {
            ticker.queue.removeIf(item -> key.equals(item.key));
            if (ticker.current != null && key.equals(ticker.current.key)) ticker.advance();
        }});
    }

    /** RootInput reports a passive tap on the real status-bar area. */
    static void openCurrent() {
        final NativeTicker ticker = active;
        if (ticker == null) return;
        ticker.ui.post(new Runnable() { public void run() {
            Item item = ticker.current;
            if (item == null) return;
            TickerListener.open(ticker.context, item.key, item.pkg, item.autoCancel);
            ticker.advance();
        }});
    }

    /** RootInput reports a passive flick away from the status-bar area. */
    static void dismissCurrent() {
        final NativeTicker ticker = active;
        if (ticker != null) ticker.ui.post(new Runnable() { public void run() { ticker.advance(); }});
    }

    /** A downward status-bar gesture keeps the stock shade's native behavior. */
    static void pullShade() {
        final NativeTicker ticker = active;
        if (ticker == null) return;
        ticker.ui.post(new Runnable() { public void run() {
            ticker.remove();
            ShadeService.notifications();
        }});
    }

    private void accept(Item item) {
        if (!Prefs.on(context, Prefs.TICKER_ON)
                || keyguard != null && keyguard.isKeyguardLocked()) return;
        int foreground = Prefs.n(context, Prefs.FRONT_MODE);
        if (foreground != 0 && item.pkg != null && item.pkg.equals(topPackage())) {
            if (foreground == 2) return;
            final Item delayed = item;
            ui.postDelayed(new Runnable() { public void run() { show(delayed); }}, 1200);
            return;
        }
        if (attached && Prefs.on(context, Prefs.QUEUE)) queue.addLast(item);
        else show(item);
    }

    private void show(Item item) {
        if (windows == null) return;
        current = item;
        int style = Prefs.n(context, Prefs.STYLE);
        int lines = Math.max(1, Math.min(style == TickerView.LOOK_CARD ? 2 : 3,
                Prefs.n(context, Prefs.LINES)));
        int textSp = Prefs.n(context, Prefs.TEXT);
        float textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSp,
                context.getResources().getDisplayMetrics());
        if (view == null) view = new TickerView(context);
        geometry();
        int status = statusBarHeight();
        // Root leases the collapsed status-bar icon regions while this is visible, so the
        // ticker owns the complete band instead of drawing underneath the clock and battery.
        view.setContentInsets(0, 0);
        view.configure(Prefs.on(context, Prefs.AUTO_PAD), Prefs.n(context, Prefs.PAD),
                Prefs.on(context, Prefs.HOP), Prefs.n(context, Prefs.HOP_GAP),
                Prefs.n(context, Prefs.SCROLL_MODE), Prefs.n(context, Prefs.SPEED),
                Prefs.n(context, Prefs.DELAY), textPx, Prefs.n(context, Prefs.OPACITY));
        Palette palette = Palette.of(context);
        view.setLook(style, palette.surfaceHigh, palette.onSurface,
                palette.onSurfaceVariant, palette.primary);
        String[] tidy = ShadeService.tidy(item.app, item.title, item.body);
        String[] bold = null;
        String[] values;
        int[] styles;
        if (style == TickerView.LOOK_CARD && lines == 1) {
            bold = new String[]{tidy[1]}; values = new String[]{tidy[2]};
            styles = new int[]{TickerView.STYLE_NORMAL};
        } else if (style == TickerView.LOOK_CARD) {
            bold = new String[]{tidy[1], ""}; values = new String[]{tidy[0], tidy[2]};
            styles = new int[]{TickerView.STYLE_MUTED, TickerView.STYLE_NORMAL};
        } else if (lines == 1) {
            values = new String[]{tidy[2].isEmpty() ? tidy[1] : tidy[1] + "  —  " + tidy[2]};
            styles = new int[]{TickerView.STYLE_NORMAL};
        } else if (lines == 2) {
            values = new String[]{tidy[1], tidy[2]};
            styles = new int[]{TickerView.STYLE_BOLD, TickerView.STYLE_NORMAL};
        } else {
            values = new String[]{tidy[0], tidy[1], tidy[2]};
            styles = new int[]{TickerView.STYLE_MUTED, TickerView.STYLE_BOLD, TickerView.STYLE_NORMAL};
        }
        Drawable icon = null;
        if (style == TickerView.LOOK_CARD && item.pkg != null) try {
            icon = context.getPackageManager().getApplicationIcon(item.pkg).mutate();
        } catch (Exception ignored) {}
        view.setContent(bold, values, styles, icon, 0);

        int height = Prefs.n(context, Prefs.HEIGHT);
        if (height <= 0) height = Math.max(statusBarHeight(),
                (int)Math.ceil(TickerView.lineHeight(textPx) * (lines + 0.3f)));
        long visibleMs = Math.max(800, Prefs.n(context, Prefs.DWELL))
                + Math.max(0, Prefs.n(context, Prefs.ANIM_MS)) + 2000L;
        coverStatusIcons(visibleMs);
        layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.START;
        layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        layout.setFitInsetsTypes(0);
        layout.setTitle("TickerBar native ticker");
        boolean fresh = !attached;
        try {
            if (fresh) { windows.addView(view, layout); attached = true; }
            else windows.updateViewLayout(view, layout);
        } catch (Throwable failure) {
            Log.e("TickerBarNative", "Could not attach native ticker", failure);
            remove();
            return;
        }
        RootInput.setTickerVisible(context, height);
        view.animate().cancel();
        animateIn(Prefs.n(context, Prefs.ANIM_IN), Prefs.n(context, Prefs.ANIM_MS), height, fresh);
        animOut = Prefs.n(context, Prefs.ANIM_OUT);
        animMs = Math.max(0, Prefs.n(context, Prefs.ANIM_MS));
        ui.removeCallbacks(hide);
        ui.postDelayed(hide, Math.max(800, Prefs.n(context, Prefs.DWELL)));
    }

    private final Runnable hide = new Runnable() { public void run() { hideAnimated(); }};

    private void animateIn(int mode, int duration, int height, boolean fresh) {
        view.setAlpha(1f); view.setTranslationX(0f); view.setTranslationY(0f); view.setRotationX(0f);
        if (!fresh) return;
        view.setCameraDistance(dp(8000));
        if (mode == 1) view.setAlpha(0f);
        else if (mode == 2) view.setTranslationY(-height);
        else if (mode == 3) view.setTranslationX(context.getResources().getDisplayMetrics().widthPixels);
        else if (mode == 4 || mode == 5) { view.setPivotY(mode == 5 ? 0f : height / 2f); view.setRotationX(90f); }
        view.animate().alpha(1f).translationX(0f).translationY(0f).rotationX(0f)
                .setDuration(Math.max(0, duration)).start();
    }

    private void hideAnimated() {
        if (!attached || view == null || animMs <= 0 || animOut == 0) { advance(); return; }
        android.view.ViewPropertyAnimator animation = view.animate().setDuration(animMs)
                .withEndAction(new Runnable() { public void run() { advance(); }});
        if (animOut == 1) animation.alpha(0f);
        else if (animOut == 2) animation.translationY(-view.getHeight());
        else if (animOut == 3) animation.translationX(-context.getResources().getDisplayMetrics().widthPixels);
        else { view.setPivotY(animOut == 5 ? 0f : view.getHeight() / 2f); animation.rotationX(-90f); }
        animation.start();
    }

    private void advance() {
        remove();
        Item next = queue.pollFirst();
        if (next != null) show(next);
    }

    private void remove() {
        ui.removeCallbacks(hide);
        if (view != null) view.animate().cancel();
        if (attached && windows != null && view != null) try { windows.removeViewImmediate(view); }
        catch (Throwable ignored) {}
        attached = false;
        current = null;
        RootInput.setTickerVisible(context, 0);
        releaseStatusIcons();
    }

    /**
     * Prefer the root daemon's socket-scoped lease. An already-running older daemon does not
     * know that protocol until the next root start, so retain a guarded shell fallback for
     * seamless APK updates. Its owner token prevents an old watchdog restoring a newer ticker.
     */
    private void coverStatusIcons(final long visibleMs) {
        statusCommands.execute(new Runnable() { public void run() {
            if (statusIconLease != null) return;   // a replacement ticker extends the same live lease
            if (!nativeIconLeaseUnsupported) {
                statusIconLease = RootShell.acquireStatusIconLease();
                if (statusIconLease != null) return;
                nativeIconLeaseUnsupported = true;
            }
            final String token = Long.toHexString(android.os.SystemClock.elapsedRealtimeNanos());
            fallbackIconToken = token;
            long watchdogSeconds = Math.max(8L, Math.min(300L, (visibleMs + 5999L) / 1000L));
            String command = "printf %s " + token + " > " + ICON_OWNER + "; "
                    + "cmd statusbar send-disable-flag system-icons clock notification-icons; "
                    + "(sleep " + watchdogSeconds + "; "
                    + "if [ \"$(cat " + ICON_OWNER + " 2>/dev/null)\" = \"" + token + "\" ]; then "
                    + "cmd statusbar send-disable-flag none; rm -f " + ICON_OWNER + "; fi) "
                    + ">/dev/null 2>&1 &";
            RootShell.Result result = RootShell.exec(command);
            if (!result.connected || result.code != 0)
                Log.w("TickerBarNative", "Could not suppress status icons: " + result.output);
        }});
    }

    private void releaseStatusIcons() {
        statusCommands.execute(new Runnable() { public void run() {
            if (statusIconLease != null) {
                statusIconLease.close();
                statusIconLease = null;
            }
            String token = fallbackIconToken;
            fallbackIconToken = null;
            if (token == null) return;
            RootShell.Result result = RootShell.exec(
                    "if [ \"$(cat " + ICON_OWNER + " 2>/dev/null)\" = \"" + token + "\" ]; then "
                            + "cmd statusbar send-disable-flag none; rm -f " + ICON_OWNER + "; fi");
            if (!result.connected || result.code != 0)
                Log.w("TickerBarNative", "Could not restore status icons: " + result.output);
        }});
    }

    private void geometry() {
        try {
            Display display = windows.getDefaultDisplay();
            DisplayCutout cutout = display == null ? null : display.getCutout();
            ArrayList<Rect> bounds = new ArrayList<Rect>();
            if (cutout != null) for (Rect rect : cutout.getBoundingRects())
                if (!rect.isEmpty()) bounds.add(new Rect(rect));
            view.setCutouts(bounds);
            if (display != null) {
                RoundedCorner left = display.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
                RoundedCorner right = display.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
                view.setCorners(left == null ? 0 : left.getRadius(), left == null ? 0 : left.getCenter().x,
                        left == null ? 0 : left.getCenter().y, right == null ? 0 : right.getRadius(),
                        right == null ? 0 : right.getCenter().x, right == null ? 0 : right.getCenter().y);
            }
        } catch (Throwable ignored) {}
    }

    private int statusBarHeight() {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id == 0 ? dp(32) : context.getResources().getDimensionPixelSize(id);
    }

    private String topPackage() {
        UsageStatsManager manager = (UsageStatsManager)context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (manager == null) return "";
        long end = System.currentTimeMillis();
        UsageEvents events = manager.queryEvents(end - 10_000, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String foreground = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) foreground = event.getPackageName();
        }
        return foreground == null ? "" : foreground;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
