package com.jakes.tickerbar;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Shader;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.RoundedCorner;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.animation.ValueAnimator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Hosts everything that must sit above system UI. Accessibility overlays are layered
 * above the status bar and navigation bar; TYPE_APPLICATION_OVERLAY is layered below
 * them. That is why the ticker and the wallet button live here.
 *
 * Keep the class name: renaming this component would silently revoke the user's
 * Accessibility grant when the app updates.
 */
public class ShadeService extends AccessibilityService {

    private static ShadeService self;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;

    // ticker
    private TickerView ticker;
    private WindowManager.LayoutParams tickerLp;
    private Item current;   // what the ticker is showing, for tap-to-open
    private boolean showing;
    private final ArrayDeque<Item> queue = new ArrayDeque<Item>();
    private final Runnable hideR = new Runnable() { public void run() { maybeHide(); } };
    private List<Rect> cutoutRects = new ArrayList<Rect>();

    // wallet button
    private ButtonView button;
    private boolean buttonAttached;

    // corner long-press zone
    private CornerView corner;
    private boolean cornerAttached;

    // foreground tracking
    private String launcherPkg = "";
    private boolean onHome = true;

    // lock state: nothing of ours appears on the lock screen
    private KeyguardManager keyguard;
    private boolean receiverOn;
    private boolean lastLocked;
    /** A lock-screen gesture queues its target; it opens on unlock if that happens in time. */
    private Intent pendingTarget;
    private long pendingUntil;
    private static final long PENDING_MS = 15000;
    private final Runnable openPendingR = new Runnable() { public void run() {
        Intent t = pendingTarget;
        pendingTarget = null;
        if (t != null) openOrQueue(t);
    }};
    // screen on/off changes visibility even when lock state doesn't, so always re-apply
    private final Runnable syncR = new Runnable() { public void run() { syncLock(); updateButton(); } };
    private final BroadcastReceiver screenR = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                pendingTarget = null;
                hideTickerNow();   // never carry notification text onto the lock screen
            }
            // The unlock broadcast can arrive before the keyguard reports unlocked (or not
            // at all), so look again a few times rather than trusting a single signal.
            syncLock();
            updateButton();
            ui.postDelayed(syncR, 300);
            ui.postDelayed(syncR, 1200);
            ui.postDelayed(syncR, 3000);
        }
    };

    private boolean isLocked() { return keyguard != null && keyguard.isKeyguardLocked(); }

    /** Re-evaluates lock state; hides everything on lock and restores the button on unlock. */
    private void syncLock() {
        boolean l = isLocked();
        if (l == lastLocked) return;
        lastLocked = l;
        Log.d("TickerBar", "lock changed locked=" + l + " pending=" + (pendingTarget != null));
        if (l) {
            hideTickerNow();
        } else if (pendingTarget != null && pendingUntil > SystemClock.uptimeMillis()) {
            ui.postDelayed(openPendingR, 150);   // let the keyguard finish going away
        } else {
            pendingTarget = null;
        }
        updateButton();
    }

    // ============================================================ lifecycle

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        self = this;
        Prefs.migrate(this);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        launcherPkg = resolveLauncher();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenR, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenR, f);
        receiverOn = true;
        lastLocked = isLocked();
        // Without reading screen content we can't ask what's in front, so guess until the
        // first window change: locked at start (a reboot) means you'll most likely unlock to
        // the home screen; unlocked (an update, toggling the service) means you're in an app.
        onHome = lastLocked;
        buildTicker();
        applySettings();
        reopenAfterUpdate();
    }

    /**
     * A self-update kills the app mid-use. Reopen it where the user was: the service is
     * allowed to start an activity from the background, the updated app isn't.
     */
    private void reopenAfterUpdate() {
        long at = Prefs.get(this).getLong(Prefs.RELAUNCH_AT, 0);
        if (at == 0) return;
        Prefs.get(this).edit().remove(Prefs.RELAUNCH_AT).apply();
        if (System.currentTimeMillis() - at > 3 * 60 * 1000L) return;
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_UPDATED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    @Override public boolean onUnbind(Intent intent) {
        teardown();
        if (self == this) self = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        teardown();
        if (self == this) self = null;
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        ui.post(new Runnable() { public void run() { applySettings(); } });   // rotation moves the cutout
        // and the nav bar, whose insets can settle a beat after the rotation itself
        ui.postDelayed(new Runnable() { public void run() { if (wm != null) updateCorner(); } }, 400);
    }

    @Override public void onInterrupt() {}

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        syncLock();   // unlocking always brings a window change, even when no broadcast arrives
        CharSequence cs = e.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        // TickerBar's own settings screen is a real app screen, not one of our overlays
        boolean ownApp = pkg.equals(getPackageName()) && e.getClassName() != null
                && MainActivity.class.getName().contentEquals(e.getClassName());
        if (!ownApp && isTransient(pkg)) return;
        boolean home = pkg.equals(launcherPkg);
        if (home != onHome) {
            onHome = home;
            updateButton();
        }
    }

    /** Windows that float over whatever app is underneath rather than replacing it. */
    private boolean isTransient(String pkg) {
        return pkg.equals(getPackageName())
                || pkg.equals("com.android.systemui")
                || pkg.equals("miui.systemui.plugin")
                || pkg.equals("android")
                || pkg.equals(imePackage());
    }

    private void teardown() {
        if (receiverOn) { try { unregisterReceiver(screenR); } catch (Exception ignored) {} receiverOn = false; }
        ui.removeCallbacksAndMessages(null);
        queue.clear();
        showing = false;
        if (wm != null) {
            if (ticker != null) { try { wm.removeViewImmediate(ticker); } catch (Exception ignored) {} }
            if (button != null && buttonAttached) {
                try { wm.removeViewImmediate(button); } catch (Exception ignored) {}
            }
            if (corner != null && cornerAttached) {
                try { wm.removeViewImmediate(corner); } catch (Exception ignored) {}
            }
        }
        ticker = null;
        buttonAttached = false;
        cornerAttached = false;
    }

    // ============================================================ public API

    public static boolean isReady() { return self != null; }

    public static boolean isOnHome() {
        ShadeService s = self;
        return s == null || s.onHome;
    }

    public static boolean notifications() {
        ShadeService s = self;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }

    public static boolean quickSettings() {
        ShadeService s = self;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }

    public static void post(Item it) {
        dispatch(it, false);
    }

    public static boolean preview() {
        ShadeService s = self;
        android.graphics.drawable.Icon ic = s == null ? null
                : android.graphics.drawable.Icon.createWithResource(s, R.drawable.ic_notif);
        return dispatch(new Item("TickerBar", "Preview",
                "A long sample line so you can watch it scroll, hop around the camera "
                        + "cut-out and clear the rounded corners", "com.jakes.tickerbar", ic), true);
    }

    private static boolean dispatch(final Item it, final boolean force) {
        final ShadeService s = self;
        if (s == null) return false;
        s.ui.post(new Runnable() { public void run() { s.enqueue(it, force); } });
        return true;
    }

    public static void refresh() {
        final ShadeService s = self;
        if (s == null) return;
        s.ui.post(new Runnable() { public void run() {
            s.launcherPkg = s.resolveLauncher();
            s.applySettings();
        }});
    }

    // ============================================================ settings

    private void applySettings() {
        if (wm == null) return;
        Display d = display();
        readGeometry(d);
        if (ticker != null) {
            float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    Prefs.n(this, Prefs.TEXT), getResources().getDisplayMetrics());
            ticker.configure(
                    Prefs.on(this, Prefs.AUTO_PAD), Prefs.n(this, Prefs.PAD),
                    Prefs.on(this, Prefs.HOP), Prefs.n(this, Prefs.HOP_GAP),
                    Prefs.n(this, Prefs.SCROLL_MODE), Prefs.n(this, Prefs.SPEED),
                    Prefs.n(this, Prefs.DELAY), px, Prefs.n(this, Prefs.OPACITY));
            Palette pal = Palette.of(this);
            ticker.setLook(Prefs.n(this, Prefs.STYLE), pal.surfaceHigh, pal.onSurface,
                    pal.onSurfaceVariant, pal.primary);
            tickerLp.height = barHeight(px);
            try { wm.updateViewLayout(ticker, tickerLp); } catch (Exception ignored) {}
        }
        updateButton();
    }

    private void readGeometry(Display d) {
        List<Rect> rects = new ArrayList<Rect>();
        if (d != null) {
            DisplayCutout dc = d.getCutout();
            if (dc != null) {
                for (Rect r : dc.getBoundingRects()) if (!r.isEmpty()) rects.add(new Rect(r));
            }
        }
        cutoutRects = rects;
        if (ticker == null) return;
        ticker.setCutouts(rects);
        if (d != null && Build.VERSION.SDK_INT >= 31) {
            RoundedCorner tl = d.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT);
            RoundedCorner tr = d.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT);
            ticker.setCorners(
                    tl == null ? 0 : tl.getRadius(), tl == null ? 0 : tl.getCenter().x,
                    tl == null ? 0 : tl.getCenter().y,
                    tr == null ? 0 : tr.getRadius(), tr == null ? 0 : tr.getCenter().x,
                    tr == null ? 0 : tr.getCenter().y);
        }
    }

    /** Auto height covers the status bar and the cut-out band, or more if the lines need it. */
    private int barHeight(float textPx) {
        int set = Prefs.n(this, Prefs.HEIGHT);
        if (set > 0) return set;
        int sb = 0;
        Resources sys = Resources.getSystem();
        int id = sys.getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) sb = sys.getDimensionPixelSize(id);
        int cut = 0;
        for (Rect r : cutoutRects) if (r.top <= 0) cut = Math.max(cut, r.bottom);
        int base = Math.max(sb, cut);
        // the heads-up card stays within the status bar; its text shrinks to fit instead
        if (Prefs.n(this, Prefs.STYLE) == TickerView.LOOK_CARD) return base;
        int lines = Math.max(1, Math.min(3, Prefs.n(this, Prefs.LINES)));
        int need = (int) Math.ceil(TickerView.lineHeight(textPx) * (lines + 0.3f));
        return Math.max(base, need);
    }

    // ============================================================ ticker

    private WindowManager.LayoutParams overlayParams(int w, int h, int extraFlags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | extraFlags,
                PixelFormat.TRANSLUCENT);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);   // don't get pushed below the status bar
        return lp;
    }

    private void buildTicker() {
        if (ticker != null || wm == null) return;
        ticker = new TickerView(this);
        ticker.setVisibility(View.GONE);
        tickerLp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, 1,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        tickerLp.gravity = Gravity.TOP | Gravity.START;
        tickerLp.setTitle("TickerBar ticker");
        ticker.setOnTouchListener(new TickerTouch());
        try { wm.addView(ticker, tickerLp); } catch (Exception ex) { ticker = null; }
    }

    /**
     * The ticker only takes touches while it's showing, so the status bar underneath
     * behaves normally the rest of the time.
     */
    private void setTickerTouchable(boolean on) {
        if (ticker == null || tickerLp == null) return;
        int flags = on ? tickerLp.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                       : tickerLp.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (flags == tickerLp.flags) return;
        tickerLp.flags = flags;
        try { wm.updateViewLayout(ticker, tickerLp); } catch (Exception ignored) {}
    }

    /**
     * Works like a heads-up notification: tap to open it, flick it up or sideways to
     * dismiss, pull down to open the shade (the ticker is covering the status bar).
     */
    private final class TickerTouch implements View.OnTouchListener {
        private float x0, y0;
        private boolean armed;

        public boolean onTouch(View v, MotionEvent e) {
            int slop = ViewConfiguration.get(ShadeService.this).getScaledTouchSlop();
            float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    x0 = e.getRawX();
                    y0 = e.getRawY();
                    armed = showing && current != null;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (armed && dy > 2 * slop && dy > Math.abs(dx)) {
                        armed = false;
                        pullShade(x0);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!armed) return true;
                    armed = false;
                    if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        openCurrent();
                    } else if (dy > slop && dy > Math.abs(dx)) {
                        pullShade(x0);
                    } else {
                        advance();
                    }
                    return true;
                default:
                    armed = false;
                    return true;
            }
        }
    }

    /** A pull down on the ticker opens the shade the status bar would have. */
    private void pullShade(float x) {
        hideTickerNow();
        // HyperOS splits the shade: pulling from the right opens Control Centre
        String make = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        boolean split = make.contains("xiaomi") || make.contains("redmi") || make.contains("poco");
        performGlobalAction(split && x > screenWidth() * 0.6f
                ? GLOBAL_ACTION_QUICK_SETTINGS : GLOBAL_ACTION_NOTIFICATIONS);
    }

    /** Open what's on the ticker, exactly as tapping it in the shade would. */
    private void openCurrent() {
        Item it = current;
        if (it == null) return;
        if (isLocked()) { hideTickerNow(); return; }
        boolean opened = false;
        if (it.intent != null) {
            try {
                android.os.Bundle opts = null;
                if (Build.VERSION.SDK_INT >= 34) {
                    // lend the app our right to start an activity from the background,
                    // the way the shade does when you tap a notification
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                    o.setPendingIntentBackgroundActivityStartMode(
                            android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    opts = o.toBundle();
                }
                it.intent.send(this, 0, null, null, null, null, opts);
                opened = true;
            } catch (Exception ignored) {
                // cancelled by the app since it posted; fall back to opening the app
            }
        }
        if (!opened && it.pkg != null) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(launch); } catch (Exception ignored) {}
            }
        }
        if (it.autoCancel) TickerListener.cancel(it.key);
        advance();
    }

    private void enqueue(Item item, boolean force) {
        if (ticker == null) return;
        if (!force && !Prefs.on(this, Prefs.TICKER_ON)) return;
        // The lock screen hides sensitive notification content; scrolling it across a
        // locked phone would bypass that, so the ticker stays quiet until unlock.
        if (isLocked()) return;
        if (showing && Prefs.on(this, Prefs.QUEUE)) {
            if (queue.size() >= 8) queue.pollFirst();
            queue.addLast(item);
            return;
        }
        display(item, !showing);
    }

    private void hideTickerNow() {
        ui.removeCallbacks(hideR);
        queue.clear();
        current = null;
        setTickerTouchable(false);
        if (ticker != null) {
            ticker.animate().cancel();
            ticker.setVisibility(View.GONE);
        }
        showing = false;
    }

    /**
     * Many apps repeat the title inside the text ("Mom" / "Mom: running late", or both the
     * same). Shown as-is, the name appears twice, one either side of the camera cut-out.
     */
    static String[] tidy(String app, String title, String body) {
        String a = app.trim(), t = title.trim(), b = body.trim();
        if (t.isEmpty()) { t = b; b = ""; }
        if (b.equalsIgnoreCase(t)) b = "";
        else if (!t.isEmpty() && b.length() > t.length() && b.regionMatches(true, 0, t, 0, t.length())) {
            String rest = b.substring(t.length()).replaceFirst("^[\\s:\\-\u2013\u2014|,]+", "");
            if (!rest.isEmpty()) b = rest;
        }
        if (a.equalsIgnoreCase(t)) a = "";
        return new String[]{a, t, b};
    }

    private void display(Item it, boolean animateIn) {
        String[] tid = tidy(it.app, it.title, it.body);
        String app = tid[0], title = tid[1], body = tid[2];
        boolean card = Prefs.n(this, Prefs.STYLE) == TickerView.LOOK_CARD;
        String[] bold, texts;
        int[] styles;
        android.graphics.drawable.Drawable icon = null;
        if (card) {
            // heads-up look: the app's own small icon, bold title, then the text
            int n = Math.max(1, Math.min(2, Prefs.n(this, Prefs.LINES)));
            if (it.icon != null) {
                try { icon = it.icon.loadDrawable(this); } catch (Exception ignored) {}
                if (icon != null) icon = icon.mutate();
            }
            if (n == 1) {
                bold = new String[]{title};
                texts = new String[]{body};
                styles = new int[]{TickerView.STYLE_NORMAL};
            } else {
                bold = new String[]{title, ""};
                texts = new String[]{app, body};
                styles = new int[]{TickerView.STYLE_MUTED, TickerView.STYLE_NORMAL};
            }
        } else {
            int n = Math.max(1, Math.min(3, Prefs.n(this, Prefs.LINES)));
            if (n == 1) {
                String one = body.isEmpty() ? title : title.isEmpty() ? body : title + "  \u2014  " + body;
                texts = new String[]{one};
                styles = new int[]{TickerView.STYLE_NORMAL};
            } else if (n == 2) {
                texts = new String[]{title, body};
                styles = new int[]{TickerView.STYLE_BOLD, TickerView.STYLE_NORMAL};
            } else {
                texts = new String[]{app, title, body};
                styles = new int[]{TickerView.STYLE_MUTED, TickerView.STYLE_BOLD, TickerView.STYLE_NORMAL};
            }
            bold = null;
        }

        int ms = Math.max(0, Prefs.n(this, Prefs.ANIM_MS));
        ticker.animate().cancel();
        ticker.setAlpha(1f);
        ticker.setTranslationX(0f);
        ticker.setTranslationY(0f);
        ticker.setRotationX(0f);
        ticker.setShade(0f);
        ticker.animate().setUpdateListener(null);
        ticker.setContent(bold, texts, styles, icon, animateIn ? ms : 0);
        showing = true;
        current = it;
        setTickerTouchable(true);
        ticker.setVisibility(View.VISIBLE);
        if (animateIn) animateIn(ms);

        ui.removeCallbacks(hideR);
        ui.postDelayed(hideR, Math.max(800, Prefs.n(this, Prefs.DWELL)) + (animateIn ? ms : 0));
    }

    private void animateIn(int ms) {
        int h = tickerLp.height;
        int w = ticker.getWidth() > 0 ? ticker.getWidth() : screenWidth();
        DecelerateInterpolator in = new DecelerateInterpolator();
        switch (Prefs.n(this, Prefs.ANIM_IN)) {
            case 1:
                ticker.setAlpha(0f);
                ticker.animate().alpha(1f).setDuration(ms).setInterpolator(in).start();
                break;
            case 2:
                ticker.setTranslationY(-h);
                ticker.animate().translationY(0f).setDuration(ms).setInterpolator(in).start();
                break;
            case 3:
                ticker.setTranslationX(w);
                ticker.animate().translationX(0f).setDuration(ms).setInterpolator(in).start();
                break;
            case 4:   // billboard: the panel's back face swings round to the front
                flip(90f, 0f, h / 2f, ms, in, null);
                break;
            case 5:   // hinged at the top edge, swinging down like a flap
                flip(-90f, 0f, 0f, ms, in, null);
                break;
            default:
                break;
        }
    }

    /** Rotates the panel about its horizontal axis, shading it as it turns edge-on. */
    private void flip(float from, float to, float pivotY, int ms,
                      android.animation.TimeInterpolator interp, Runnable end) {
        float density = getResources().getDisplayMetrics().density;
        int w = ticker.getWidth() > 0 ? ticker.getWidth() : screenWidth();
        ticker.setPivotX(w / 2f);
        ticker.setPivotY(pivotY);
        ticker.setCameraDistance(6000f * density);   // mild perspective; lower looks fish-eyed
        ticker.setRotationX(from);
        ticker.setShade(shadeFor(from));
        android.view.ViewPropertyAnimator a = ticker.animate().rotationX(to).setDuration(ms)
                .setInterpolator(interp)
                .setUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(ValueAnimator va) {
                        if (ticker != null) ticker.setShade(shadeFor(ticker.getRotationX()));
                    }
                });
        if (end != null) a.withEndAction(end);
        a.start();
    }

    private static float shadeFor(float degrees) {
        return (float) Math.abs(Math.sin(Math.toRadians(degrees))) * 0.6f;
    }

    private void maybeHide() {
        if (ticker == null) return;
        long r = ticker.remainingMs();
        if (r > 0) { ui.postDelayed(hideR, r); return; }   // let the scroll finish first
        advance();
    }

    /** Send the current notification off and bring on the next one, if any. */
    private void advance() {
        if (ticker == null) return;
        ui.removeCallbacks(hideR);
        current = null;
        setTickerTouchable(false);
        final Item next = queue.pollFirst();
        animateOut(new Runnable() { public void run() {
            if (ticker == null) return;
            if (next != null) {
                display(next, true);
            } else {
                ticker.setVisibility(View.GONE);
                showing = false;
            }
        }});
    }

    private void animateOut(Runnable end) {
        int ms = Math.max(0, Prefs.n(this, Prefs.ANIM_MS));
        int w = ticker.getWidth() > 0 ? ticker.getWidth() : screenWidth();
        AccelerateInterpolator out = new AccelerateInterpolator();
        ticker.animate().cancel();
        ticker.animate().setUpdateListener(null);
        switch (Prefs.n(this, Prefs.ANIM_OUT)) {
            case 1:
                ticker.animate().alpha(0f).setDuration(ms).setInterpolator(out).withEndAction(end).start();
                break;
            case 2:
                ticker.animate().translationY(-tickerLp.height).setDuration(ms)
                        .setInterpolator(out).withEndAction(end).start();
                break;
            case 3:
                ticker.animate().translationX(-w).setDuration(ms)
                        .setInterpolator(out).withEndAction(end).start();
                break;
            case 4:   // keep turning the same way, so the status bar comes back round
                flip(0f, -90f, tickerLp.height / 2f, ms, out, end);
                break;
            case 5:   // flap swings back up into the top edge
                flip(0f, -90f, 0f, ms, out, end);
                break;
            default:
                end.run();
                break;
        }
    }

    // ============================================================ wallet button

    private void updateButton() {
        if (wm == null) return;
        updateCorner();
        // Samsung-style rules: home screen and/or lock screen, never inside apps.
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOn = pm == null || pm.isInteractive();
        boolean locked = isLocked();
        boolean want = Prefs.on(this, Prefs.SWIPE_ON) && screenOn
                && (locked ? Prefs.on(this, Prefs.CARD_LOCK)
                           : Prefs.on(this, Prefs.CARD_HOME) && onHome);
        if (!want) {
            if (button != null && buttonAttached) {
                try { wm.removeViewImmediate(button); } catch (Exception ignored) {}
            }
            buttonAttached = false;
            return;
        }
        if (button == null) button = new ButtonView();
        WindowManager.LayoutParams lp = buttonParams();
        try {
            if (buttonAttached) wm.updateViewLayout(button, lp);
            else { wm.addView(button, lp); buttonAttached = true; }
        } catch (Exception ignored) {}
        button.invalidate();
    }

    private WindowManager.LayoutParams buttonParams() {
        int sw = screenWidth();
        int pct = Math.max(5, Math.min(100, Prefs.n(this, Prefs.SWIPE_W)));
        int w = Math.max(dp(40), sw * pct / 100);
        // Exactly the nav bar: nothing above it, so the dock and home screen stay clear.
        int h = Prefs.n(this, Prefs.SWIPE_H);
        if (h <= 0) h = Math.max(dp(24), navZoneHeight());
        if (button != null) button.navLine = h;   // the card comes up from below the screen
        WindowManager.LayoutParams lp = overlayParams(w, h,
                passing ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        int pos = Prefs.n(this, Prefs.SWIPE_POS);
        lp.gravity = Gravity.BOTTOM
                | (pos == 0 ? Gravity.LEFT : pos == 1 ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT);
        lp.y = Math.max(0, Prefs.n(this, Prefs.SWIPE_Y));
        lp.setTitle("TickerBar wallet button");
        return lp;
    }

    /** The gesture / navigation insets on each edge; the 3-button bar moves to a side in landscape. */
    private Insets navInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return wm.getCurrentWindowMetrics().getWindowInsets()
                        .getInsets(WindowInsets.Type.mandatorySystemGestures()
                                | WindowInsets.Type.navigationBars());
            } catch (Exception ignored) {}
        }
        return Insets.NONE;
    }

    /** Height of the bottom gesture / navigation zone. */
    private int navZoneHeight() {
        int inset = navInsets().bottom;
        if (inset <= 0) {
            Resources sys = Resources.getSystem();
            int id = sys.getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) inset = sys.getDimensionPixelSize(id);
        }
        return inset > 0 ? inset : dp(48);
    }

    static final String GOOGLE_WALLET = "com.google.android.apps.walletnfcrel";
    /** Wallet's own "hold to reader" entry point, showing the default card. Undocumented. */
    static final String WALLET_QUICKDRAW = "com.google.android.apps.wallet.main.QUICKDRAW";

    /** The wallet card's target: Google Wallet's default card if possible, else the app. */
    private void launchTarget() {
        String pkg = Prefs.str(this, Prefs.SWIPE_PKG).trim();
        Intent i = null;
        if (Prefs.on(this, Prefs.WALLET_QUICK) && GOOGLE_WALLET.equals(pkg)) {
            Intent q = new Intent(WALLET_QUICKDRAW).setPackage(pkg);
            if (q.resolveActivity(getPackageManager()) != null) i = q;   // else Wallet renamed it
        }
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, "Wallet card: not installed \u2013 " + pkg, Toast.LENGTH_LONG).show();
            return;
        }
        openOrQueue(i);
    }

    /**
     * Opens a target now, or on the lock screen asks for an unlock and opens it afterwards.
     * Nothing we launch ever appears over the lock screen itself.
     */
    private void openOrQueue(Intent target) {
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (isLocked()) {
            // The system only sometimes treats our gesture as an unlock, so always ask;
            // a request alongside its own prompt is harmless.
            pendingTarget = target;
            pendingUntil = SystemClock.uptimeMillis() + PENDING_MS;
            Log.d("TickerBar", "locked: queued target, requesting unlock");
            Intent t = new Intent(this, WalletLaunchActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try { startActivity(t); } catch (Exception ignored) {}
            return;
        }
        try { startActivity(target); }
        catch (Exception ex) { Toast.makeText(this, "Couldn't open that", Toast.LENGTH_LONG).show(); }
    }

    // ------------------------------------------------------------ pass-through

    /**
     * Our touch zones sit on top of the nav bar, so anything that isn't our own gesture has
     * to reach the real nav buttons underneath. We make our windows untouchable for a moment
     * and replay the press where it happened, with the same duration so long-presses survive.
     */
    private boolean passing, passHitSelf;
    private int passTries;
    private float passX, passY;
    private long passDur;

    void passThrough(float x, float y, long durationMs) {
        if (passing) return;
        passing = true;
        passX = x;
        passY = y;
        passDur = Math.max(1, Math.min(1500, durationMs));
        passTries = 0;
        setZonesTouchable(false);
        injectLater(48);   // give the flag change a moment to reach the input system
    }

    private void injectLater(long delay) {
        ui.postDelayed(new Runnable() { public void run() {
            passHitSelf = false;
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(passX, passY);
            android.accessibilityservice.GestureDescription g =
                    new android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(new android.accessibilityservice.GestureDescription
                                    .StrokeDescription(p, 0, passDur))
                            .build();
            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(android.accessibilityservice.GestureDescription d) { afterInject(); }
                @Override public void onCancelled(android.accessibilityservice.GestureDescription d) { afterInject(); }
            }, ui);
            if (!ok) endPass();
        }}, delay);
    }

    /**
     * If the replayed press landed on our own zone, the untouchable flag hadn't reached the
     * input system yet; try again with a longer gap instead of dropping the user's press.
     */
    private void afterInject() {
        if (passHitSelf && passTries < 3) {
            passTries++;
            Log.d("TickerBar", "pass-through hit our own zone; retry " + passTries);
            injectLater(80L * passTries);
            return;
        }
        endPass();
    }

    /** Zones call this first: while a replay is in flight, touches reaching us are our own replay. */
    boolean swallowIfPassing(MotionEvent e) {
        if (!passing) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) passHitSelf = true;
        return true;
    }

    private void endPass() {
        ui.postDelayed(new Runnable() { public void run() {
            passing = false;
            setZonesTouchable(true);
        }}, 40);
    }

    private void setZonesTouchable(boolean on) {
        View[] zones = {buttonAttached ? button : null, cornerAttached ? corner : null};
        for (View v : zones) {
            if (v == null) continue;
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            if (on) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try { wm.updateViewLayout(v, lp); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------ card graphic

    private CardArt cardArt;

    /** The wallet card in the user's chosen finish (see {@link CardArt}). */
    private void drawCard(Canvas c, RectF r, float alpha) {
        if (cardArt == null) cardArt = new CardArt(this);
        alpha *= Math.max(5, Math.min(100, Prefs.n(this, Prefs.CARD_OPACITY))) / 100f;
        cardArt.draw(c, r, alpha, Prefs.n(this, Prefs.CARD_STYLE), Prefs.str(this, Prefs.CARD_LABEL));
    }

    /**
     * Fit the card's label into the strip that shows at rest, so it reads without lifting
     * the card; it stays put relative to the card as it lifts and slides out.
     */
    private void fitLabel(float visible, float cardH) {
        if (cardArt == null) cardArt = new CardArt(this);
        float size = Math.min(cardH * 0.085f, Math.max(dp(9), visible * 0.45f));
        cardArt.labelSize = size;
        cardArt.labelBaseline = Math.min(cardH * 0.165f, visible / 2f + size * 0.36f);
    }

    /** The card fills almost the whole button window's width. */
    private float cardWidth(float windowW) { return windowW * 0.96f; }

    /** How much of the card shows at rest, rising from the bottom edge of the screen. */
    private float peek(int windowH) {
        int set = Prefs.n(this, Prefs.CARD_PEEK);
        return set > 0 ? Math.min(set, windowH) : Math.round(windowH * 0.45f);
    }

    /**
     * A payment card tucked into the bottom edge, Samsung Pay style. Half of it peeks
     * out; it lifts when touched and follows the finger, then slides out on a swipe.
     */
    private final class ButtonView extends View {
        private final RectF card = new RectF();
        private final int slop;
        private float downX, downY, lastY, lift;
        private long downT;
        private boolean fired, pressed, pulled, moved;
        private ValueAnimator liftAnim;

        ButtonView() {
            super(ShadeService.this);
            slop = ViewConfiguration.get(ShadeService.this).getScaledTouchSlop();
        }

        int navLine;   // y (in this window) the card hides below: the bottom edge of the screen

        private float restTop() { return navLine - peek(getHeight()); }

        private float maxLift() { return Math.max(0f, restTop() - dp(2)); }

        @Override protected void onDraw(Canvas c) {
            if (pulled || !Prefs.on(ShadeService.this, Prefs.SWIPE_PILL)) return;
            float w = getWidth();
            float cw = cardWidth(w), ch = cw * 0.63f;
            float top = restTop() - Math.min(lift, maxLift());
            card.set((w - cw) / 2f, top, (w + cw) / 2f, top + ch);
            fitLabel(peek(getHeight()), ch);
            c.save();
            c.clipRect(0, 0, w, navLine);          // tucked behind the nav bar
            drawCard(c, card, pressed ? 1f : 0.9f);
            c.restore();
        }

        private void animateLift(float to) {
            if (liftAnim != null) liftAnim.cancel();
            liftAnim = ValueAnimator.ofFloat(lift, to);
            liftAnim.setDuration(160);
            liftAnim.setInterpolator(new DecelerateInterpolator());
            liftAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(ValueAnimator a) {
                    lift = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            liftAnim.start();
        }

        private final Runnable launchR = new Runnable() { public void run() { launchTarget(); } };

        private void fire(long delayMs) {
            fired = true;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (liftAnim != null) liftAnim.cancel();
            pullOut(this, new RectF(card));
            pulled = true;
            invalidate();
            ui.postDelayed(launchR, Math.max(0, delayMs));
        }

        void reset() {
            pulled = false;
            pressed = false;
            lift = 0f;
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (swallowIfPassing(e)) return true;
            int trig = Prefs.n(ShadeService.this, Prefs.SWIPE_TRIGGER);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    lastY = downY;
                    downT = SystemClock.uptimeMillis();
                    fired = false;
                    moved = false;
                    pressed = true;
                    animateLift(dp(5));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lastY = e.getRawY();
                    if (Math.abs(e.getRawX() - downX) > slop || Math.abs(lastY - downY) > slop) moved = true;
                    if (!fired && trig != 1) {
                        if (liftAnim != null) liftAnim.cancel();
                        lift = Math.max(0f, downY - lastY) + dp(5);   // card follows the finger
                        invalidate();
                        // only reached when nothing steals the gesture
                        if (downY - lastY >= Prefs.n(ShadeService.this, Prefs.SWIPE_DIST)) fire(220);
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    long held = SystemClock.uptimeMillis() - downT;
                    if (!fired && trig != 2 && !moved && held < 500) fire(180);
                    if (!fired) {
                        pressed = false;
                        animateLift(0f);
                        // not our gesture: hand the press to the nav bar underneath
                        if (!moved) passThrough(downX, downY, held);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    // The system's gesture recogniser steals upward swipes in its gesture
                    // zone from every window, even ones layered above the nav bar, and we
                    // get CANCEL before our threshold. A gesture that began on the card and
                    // was heading up can only be a swipe on the card, so honour it once the
                    // system's own gesture has settled.
                    if (!fired && trig != 1 && downY - lastY > 0
                            && SystemClock.uptimeMillis() - downT < 1500) {
                        fire(450);
                    } else if (!fired) {
                        pressed = false;
                        animateLift(0f);
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    /** The card sliding up out of its slot, drawn in a tall untouchable window. */
    private void pullOut(final ButtonView from, final RectF start) {
        final int winH = Math.round(screenHeight() * 0.6f);
        final int offset = winH - from.getHeight();   // button-window coords -> this window's
        final int clipBottom = offset + from.navLine; // keep the card behind the nav bar
        final View v = new View(this) {
            float t;
            {
                ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
                a.setDuration(340);
                a.setInterpolator(new DecelerateInterpolator(1.4f));
                a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(ValueAnimator va) {
                        t = (Float) va.getAnimatedValue();
                        invalidate();
                    }
                });
                a.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator an) {
                        finishPull(from);
                    }
                });
                a.start();
            }
            final RectF r = new RectF();
            @Override protected void onDraw(Canvas c) {
                float rise = t * winH * 0.55f;
                float scale = 1f + 0.06f * t;
                float cx = start.centerX(), cy = start.centerY() + offset - rise;
                float hw = start.width() * scale / 2f, hh = start.height() * scale / 2f;
                r.set(cx - hw, cy - hh, cx + hw, cy + hh);
                float alpha = t < 0.55f ? 1f : Math.max(0f, 1f - (t - 0.55f) / 0.45f);
                c.save();
                c.clipRect(0, 0, getWidth(), clipBottom);
                drawCard(c, r, alpha);
                c.restore();
            }
        };
        WindowManager.LayoutParams blp = (WindowManager.LayoutParams) from.getLayoutParams();
        WindowManager.LayoutParams lp = overlayParams(blp.width, winH,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        lp.gravity = blp.gravity;
        lp.y = blp.y;
        lp.setTitle("TickerBar card");
        pullView = v;
        try { wm.addView(v, lp); } catch (Exception ex) { pullView = null; from.reset(); }
    }

    private View pullView;

    private void finishPull(ButtonView from) {
        if (pullView != null && wm != null) {
            try { wm.removeViewImmediate(pullView); } catch (Exception ignored) {}
        }
        pullView = null;
        from.reset();
    }

    private int screenHeight() {
        Display d = display();
        if (d == null) return getResources().getDisplayMetrics().heightPixels;
        Point p = new Point();
        d.getRealSize(p);
        return p.y;
    }

    // ============================================================ corner long-press

    /** A small invisible zone in a nav-bar corner. Long-press runs its action, on any screen. */
    private void updateCorner() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean want = Prefs.on(this, Prefs.CORNER_ON) && (pm == null || pm.isInteractive());
        if (!want) {
            if (corner != null && cornerAttached) {
                try { wm.removeViewImmediate(corner); } catch (Exception ignored) {}
            }
            cornerAttached = false;
            return;
        }
        if (corner == null) corner = new CornerView();
        // length along the bar: sized off the short edge so it's the same in either orientation
        int len = Prefs.n(this, Prefs.CORNER_W);
        if (len <= 0) len = Math.max(dp(56), Math.round(Math.min(screenWidth(), screenHeight()) * 0.15f));
        boolean right = Prefs.n(this, Prefs.CORNER_SIDE) != 0;
        int flags = passing ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0;
        Insets nav = navInsets();
        WindowManager.LayoutParams lp;
        if (Math.max(nav.left, nav.right) > nav.bottom) {
            // Landscape 3-button nav stands up along a side. Run the zone down it, at the end
            // that holds the same button it covered in portrait (the buttons don't move either).
            lp = overlayParams(Math.max(dp(24), Math.max(nav.left, nav.right)), len, flags);
            Display d = display();
            boolean seascape = d != null && d.getRotation() == Surface.ROTATION_270;
            lp.gravity = (nav.right >= nav.left ? Gravity.RIGHT : Gravity.LEFT)
                    | (right != seascape ? Gravity.TOP : Gravity.BOTTOM);
        } else {
            lp = overlayParams(len, Math.max(dp(24), navZoneHeight()), flags);
            lp.gravity = Gravity.BOTTOM | (right ? Gravity.RIGHT : Gravity.LEFT);
        }
        lp.setTitle("TickerBar corner");
        try {
            if (cornerAttached) wm.updateViewLayout(corner, lp);
            else { wm.addView(corner, lp); cornerAttached = true; }
        } catch (Exception ignored) {}
        corner.invalidate();
    }

    private void runCornerAction() {
        if (Prefs.n(this, Prefs.CORNER_ACTION) == 1) {
            String pkg = Prefs.str(this, Prefs.CORNER_PKG).trim();
            Intent i = pkg.isEmpty() ? null : getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Toast.makeText(this, "Corner: app not installed \u2013 " + pkg, Toast.LENGTH_LONG).show();
                return;
            }
            openOrQueue(i);
        } else {
            openOrQueue(SearchActivity.searchIntent(this));
        }
    }

    private final class CornerView extends View {
        private final int slop;
        private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX, downY;
        private long downT;
        private boolean fired, moved;
        private final Runnable longPressR = new Runnable() { public void run() {
            if (moved || fired) return;
            fired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            runCornerAction();
        }};

        // double-tap mode: a first tap waits briefly for a second before reaching the button
        private long firstTapAt;
        private float firstX, firstY;
        private long firstDur;
        private final Runnable firstTapR = new Runnable() { public void run() {
            firstTapAt = 0;
            passThrough(firstX, firstY, firstDur);
        }};

        private boolean doubleTap() {
            return Prefs.n(ShadeService.this, Prefs.CORNER_TRIGGER) == 1;
        }

        CornerView() {
            super(ShadeService.this);
            slop = ViewConfiguration.get(ShadeService.this).getScaledTouchSlop();
            hint.setColor(0x55FFFFFF);
        }

        @Override protected void onDraw(Canvas c) {
            if (!Prefs.on(ShadeService.this, Prefs.CORNER_HINT)) return;
            float r = dp(3);
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, hint);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (swallowIfPassing(e)) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    downT = SystemClock.uptimeMillis();
                    fired = false;
                    moved = false;
                    if (!doubleTap()) {
                        postDelayed(longPressR, Math.max(150, Prefs.n(ShadeService.this, Prefs.CORNER_MS)));
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - downX) > slop || Math.abs(e.getRawY() - downY) > slop) {
                        moved = true;
                        removeCallbacks(longPressR);
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    removeCallbacks(longPressR);
                    if (fired || moved) return true;
                    long now = SystemClock.uptimeMillis(), held = now - downT;
                    int window = ViewConfiguration.getDoubleTapTimeout();
                    if (doubleTap() && held < window) {
                        boolean second = firstTapAt > 0 && now - firstTapAt <= window + held
                                && Math.abs(downX - firstX) < 4 * slop && Math.abs(downY - firstY) < 4 * slop;
                        if (second) {                              // double-tap: ours
                            removeCallbacks(firstTapR);
                            firstTapAt = 0;
                            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            runCornerAction();
                        } else {                                   // maybe the first of two
                            firstTapAt = now;
                            firstX = downX;
                            firstY = downY;
                            firstDur = held;
                            postDelayed(firstTapR, window);
                        }
                        return true;
                    }
                    // anything else belongs to the nav button underneath
                    passThrough(downX, downY, held);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    removeCallbacks(longPressR);   // the system claimed a swipe
                    return true;
                default:
                    return true;
            }
        }
    }

    // ============================================================ helpers

    private String resolveLauncher() {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri == null || ri.activityInfo == null) return "";
        String p = ri.activityInfo.packageName;
        return "android".equals(p) ? "" : p;
    }

    private String imePackage() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (id == null) return "";
        int slash = id.indexOf('/');
        return slash > 0 ? id.substring(0, slash) : id;
    }

    private Display display() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        return dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
    }

    private int screenWidth() {
        Display d = display();
        if (d == null) return getResources().getDisplayMetrics().widthPixels;
        Point p = new Point();
        d.getRealSize(p);
        return p.x;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
