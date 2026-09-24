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
    private boolean showing;
    private final ArrayDeque<String[]> queue = new ArrayDeque<String[]>();
    private final Runnable hideR = new Runnable() { public void run() { maybeHide(); } };
    private List<Rect> cutoutRects = new ArrayList<Rect>();

    // wallet button
    private ButtonView button;
    private boolean buttonAttached;

    // foreground tracking
    private String launcherPkg = "";
    private boolean onHome = true;

    // lock state: nothing of ours appears on the lock screen
    private KeyguardManager keyguard;
    private boolean receiverOn;
    private boolean lastLocked;
    /** A lock-screen swipe queues the launch; it fires on unlock if that happens in time. */
    private long pendingLaunchUntil;
    private static final long PENDING_MS = 15000;
    private final Runnable launchNowR = new Runnable() { public void run() { launchTarget(); } };
    // screen on/off changes visibility even when lock state doesn't, so always re-apply
    private final Runnable syncR = new Runnable() { public void run() { syncLock(); updateButton(); } };
    private final BroadcastReceiver screenR = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) pendingLaunchUntil = 0;
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
        Log.d("TickerBar", "lock changed locked=" + l + " pending=" + (pendingLaunchUntil > SystemClock.uptimeMillis()));
        if (l) {
            hideTickerNow();
        } else if (pendingLaunchUntil > SystemClock.uptimeMillis()) {
            pendingLaunchUntil = 0;
            ui.postDelayed(launchNowR, 150);   // let the keyguard finish going away
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
    }

    @Override public void onInterrupt() {}

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        syncLock();   // unlocking always brings a window change, even when no broadcast arrives
        CharSequence cs = e.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        if (isTransient(pkg)) return;
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
        }
        ticker = null;
        buttonAttached = false;
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

    public static void post(String app, String title, String body) {
        dispatch(app, title, body, false);
    }

    public static boolean preview() {
        return dispatch("TickerBar", "Preview",
                "A long sample line so you can watch it scroll, hop around the camera "
                        + "cut-out and clear the rounded corners", true);
    }

    private static boolean dispatch(final String app, final String title, final String body,
                                    final boolean force) {
        final ShadeService s = self;
        if (s == null) return false;
        s.ui.post(new Runnable() { public void run() { s.enqueue(app, title, body, force); } });
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
        int lines = Math.max(1, Math.min(3, Prefs.n(this, Prefs.LINES)));
        int need = (int) Math.ceil(TickerView.lineHeight(textPx) * (lines + 0.3f));
        return Math.max(Math.max(sb, cut), need);
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
        try { wm.addView(ticker, tickerLp); } catch (Exception ex) { ticker = null; }
    }

    private void enqueue(String app, String title, String body, boolean force) {
        if (ticker == null) return;
        if (!force && !Prefs.on(this, Prefs.TICKER_ON)) return;
        // The lock screen hides sensitive notification content; scrolling it across a
        // locked phone would bypass that, so the ticker stays quiet until unlock.
        if (isLocked()) return;
        String[] item = new String[]{
                app == null ? "" : app, title == null ? "" : title, body == null ? "" : body};
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
        if (ticker != null) {
            ticker.animate().cancel();
            ticker.setVisibility(View.GONE);
        }
        showing = false;
    }

    private void display(String[] it, boolean animateIn) {
        int n = Math.max(1, Math.min(3, Prefs.n(this, Prefs.LINES)));
        String app = it[0], title = it[1], body = it[2];
        String[] texts;
        int[] styles;
        if (n == 1) {
            String one = body.isEmpty() ? title : title.isEmpty() ? body : title + "  —  " + body;
            texts = new String[]{one};
            styles = new int[]{TickerView.STYLE_NORMAL};
        } else if (n == 2) {
            texts = new String[]{title, body};
            styles = new int[]{TickerView.STYLE_BOLD, TickerView.STYLE_NORMAL};
        } else {
            texts = new String[]{app, title, body};
            styles = new int[]{TickerView.STYLE_MUTED, TickerView.STYLE_BOLD, TickerView.STYLE_NORMAL};
        }

        int ms = Math.max(0, Prefs.n(this, Prefs.ANIM_MS));
        ticker.animate().cancel();
        ticker.setAlpha(1f);
        ticker.setTranslationX(0f);
        ticker.setTranslationY(0f);
        ticker.setRotationX(0f);
        ticker.setShade(0f);
        ticker.animate().setUpdateListener(null);
        ticker.setContent(texts, styles, animateIn ? ms : 0);
        showing = true;
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
        final String[] next = queue.pollFirst();
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
        int h = Prefs.n(this, Prefs.SWIPE_H);
        if (h <= 0) h = Math.max(dp(32), navZoneHeight()) + dp(14);
        WindowManager.LayoutParams lp = overlayParams(w, h, 0);
        int pos = Prefs.n(this, Prefs.SWIPE_POS);
        lp.gravity = Gravity.BOTTOM
                | (pos == 0 ? Gravity.LEFT : pos == 1 ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT);
        lp.y = Math.max(0, Prefs.n(this, Prefs.SWIPE_Y));
        lp.setTitle("TickerBar wallet button");
        return lp;
    }

    /** Height of the bottom gesture / navigation zone. */
    private int navZoneHeight() {
        int inset = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Insets in = wm.getCurrentWindowMetrics().getWindowInsets()
                        .getInsets(WindowInsets.Type.mandatorySystemGestures()
                                | WindowInsets.Type.navigationBars());
                inset = in.bottom;
            } catch (Exception ignored) {}
        }
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

    private void launchTarget() {
        if (isLocked()) {
            // Never open over the lock screen. Queue the launch for when the phone unlocks,
            // and always ask for an unlock: the system only sometimes treats a swipe on the
            // card as an unlock gesture, and a second request alongside its own is harmless.
            pendingLaunchUntil = SystemClock.uptimeMillis() + PENDING_MS;
            Log.d("TickerBar", "launch while locked: queued + starting trampoline");
            Intent t = new Intent(this, WalletLaunchActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try { startActivity(t); } catch (Exception ignored) {}
            return;
        }
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
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); }
        catch (Exception ex) { Toast.makeText(this, "Couldn't open " + pkg, Toast.LENGTH_LONG).show(); }
    }

    // ------------------------------------------------------------ card graphic

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpR = new RectF();

    /** A payment card: gradient face, gold chip, faint sheen and edge. */
    private void drawCard(Canvas c, RectF r, float alpha) {
        float rad = r.width() * 0.09f;
        Paint p = cardPaint;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                0xFF4F6BFF, 0xFF9B5CFF, Shader.TileMode.CLAMP));
        p.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);

        p.setColor(0xFFFFFFFF);                                   // sheen across the top
        p.setAlpha(Math.round(38 * alpha));
        tmpR.set(r.left, r.top, r.right, r.top + r.height() * 0.22f);
        c.drawRoundRect(tmpR, rad, rad, p);

        float chipW = r.width() * 0.2f, chipH = chipW * 0.76f;   // chip sits in the part that peeks out
        tmpR.set(r.left + r.width() * 0.12f, r.top + r.height() * 0.26f,
                r.left + r.width() * 0.12f + chipW, r.top + r.height() * 0.26f + chipH);
        p.setColor(0xFFE9C46A);
        p.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(tmpR, chipW * 0.2f, chipW * 0.2f, p);

        p.setStyle(Paint.Style.STROKE);                          // edge, so it reads on dark wallpaper
        p.setStrokeWidth(dp(1));
        p.setColor(0xFFFFFFFF);
        p.setAlpha(Math.round(70 * alpha));
        c.drawRoundRect(r, rad, rad, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Card size for a button window of the given width. */
    private float cardWidth(float windowW) { return Math.min(windowW * 0.8f, dp(64)); }

    /**
     * A payment card tucked into the bottom edge, Samsung Pay style. Half of it peeks
     * out; it lifts when touched and follows the finger, then slides out on a swipe.
     */
    private final class ButtonView extends View {
        private final RectF card = new RectF();
        private final int slop;
        private float downX, downY, lastY, lift;
        private long downT;
        private boolean fired, pressed, pulled;
        private ValueAnimator liftAnim;

        ButtonView() {
            super(ShadeService.this);
            slop = ViewConfiguration.get(ShadeService.this).getScaledTouchSlop();
        }

        private float restTop() {
            float ch = cardWidth(getWidth()) * 0.63f;
            return getHeight() - ch * 0.5f;           // half the card below the edge
        }

        private float maxLift() { return Math.max(0f, restTop() - dp(2)); }

        @Override protected void onDraw(Canvas c) {
            if (pulled || !Prefs.on(ShadeService.this, Prefs.SWIPE_PILL)) return;
            float w = getWidth();
            float cw = cardWidth(w), ch = cw * 0.63f;
            float top = restTop() - Math.min(lift, maxLift());
            card.set((w - cw) / 2f, top, (w + cw) / 2f, top + ch);
            drawCard(c, card, pressed ? 1f : 0.88f);
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
            int trig = Prefs.n(ShadeService.this, Prefs.SWIPE_TRIGGER);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    lastY = downY;
                    downT = SystemClock.uptimeMillis();
                    fired = false;
                    pressed = true;
                    animateLift(dp(5));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lastY = e.getRawY();
                    if (!fired && trig != 1) {
                        if (liftAnim != null) liftAnim.cancel();
                        lift = Math.max(0f, downY - lastY) + dp(5);   // card follows the finger
                        invalidate();
                        // only reached when nothing steals the gesture
                        if (downY - lastY >= Prefs.n(ShadeService.this, Prefs.SWIPE_DIST)) fire(220);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!fired && trig != 2
                            && Math.abs(e.getRawX() - downX) < slop
                            && Math.abs(e.getRawY() - downY) < slop
                            && SystemClock.uptimeMillis() - downT < 500) {
                        fire(180);
                    }
                    if (!fired) { pressed = false; animateLift(0f); }
                    return true;
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
        final int winH = Math.round(screenHeight() * 0.45f);
        final int offset = winH - from.getHeight();   // button-window coords -> this window's
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
                float scale = 1f + 0.28f * t;
                float cx = start.centerX(), cy = start.centerY() + offset - rise;
                float hw = start.width() * scale / 2f, hh = start.height() * scale / 2f;
                r.set(cx - hw, cy - hh, cx + hw, cy + hh);
                float alpha = t < 0.45f ? 1f : Math.max(0f, 1f - (t - 0.45f) / 0.55f);
                drawCard(c, r, alpha);
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
