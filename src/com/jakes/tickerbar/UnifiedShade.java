package com.jakes.tickerbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.session.MediaController;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * One shade, the way stock Android does it: quick settings on top (a first row, and a grid
 * that expands under it), brightness, then the notifications. HyperOS splits these into
 * two panels; this replaces both when the user pulls down from the status bar.
 *
 * Drawn as an accessibility overlay so it sits above everything. Toggles Android lets an
 * app change (flashlight, Do Not Disturb, ringer, rotation, brightness) work in place; the
 * radios Android keeps to itself (Wi-Fi, Bluetooth, data, airplane, location) show their
 * state and open the system's own panel for them.
 */
final class UnifiedShade {

    // ------------------------------------------------------------ tiles

    static final String[] TILE_IDS = {"hotspot", "wifi", "data", "bluetooth", "recorder", "flashlight",
            "dnd", "sound", "rotate", "location", "nfc", "reading", "airplane", "battery", "ultra", "reverse"};
    static final String[] TILE_NAMES = {"Hotspot", "Internet", "Mobile data", "Bluetooth", "Screen recorder",
            "Flashlight", "Do Not Disturb", "Sound", "Auto-rotate", "Location", "NFC", "Reading mode",
            "Airplane mode", "Battery Saver", "Ultra battery saver", "Reverse charging"};
    /** Where a tile shows: 0 first row and expanded, 1 expanded only, 2 hidden. */
    static final int PLACE_BOTH = 0, PLACE_GRID = 1, PLACE_HIDDEN = 2;
    static final int ROW_MAX = 6;

    static String placeKey(String id) { return "qs_place_" + id; }

    /** Accent choices; 0 is the system's own (Material You). */
    static final int[] ACCENTS = {0, 0xFF4F7DF3, 0xFF14A8A0, 0xFF3FA35B, 0xFFE5A50A, 0xFFEE6A50,
            0xFFE0559A, 0xFF8B5CF6, 0xFF8A8F98};
    static final String[] ACCENT_NAMES = {"Match system", "Blue", "Teal", "Green", "Amber", "Coral",
            "Pink", "Purple", "Graphite"};
    private int panelA = 0xF2;
    private int notificationTextColor;
    private static final int HOME_WIDGET_HOST_ID = 0x5442;
    private static final ComponentName HOME_WIDGET_PROVIDER = new ComponentName(
            "com.google.android.apps.chromecast.app",
            "com.google.android.apps.chromecast.app.appwidget.favorites.FavoritesWidgetReceiver");
    private static final float HOME_WIDGET_SCALE = 0.90f;
    private static final int HOME_WIDGET_LOGICAL_HEIGHT_DP = 108;

    static int defaultPlace(int index) { return index < ROW_MAX ? PLACE_BOTH : PLACE_GRID; }

    private interface Tile {
        int icon();
        String label();
        String state();      // secondary text
        boolean active();
        void tap();
    }

    // ------------------------------------------------------------ state

    private final ShadeService svc;
    private Palette pal;
    private FrameLayout root;
    private View scrim, navBackdrop, quickPanel, headerView, homeShelfView;
    private WindowManager navBackdropWm;
    private LinearLayout sheet, content, notifBox;
    private FrameLayout tileStage;
    private ScrollView scroll;
    private ImageView chevron;
    private SeekBar brightness;
    /** Live range reported by DisplayPowerController; normal-mode max is below the HBM max. */
    private float brightnessRangeMin = 0.0004f;
    private float brightnessRangeMax = 0.412f;
    private boolean brightnessPreviewActive;
    private boolean brightnessLevelQueried;
    /** Coalesces Binder calls so a fast finger never starts one root process per pixel. */
    private final Object brightnessPreviewLock = new Object();
    private float pendingBrightnessPreview;
    private int pendingBrightnessTransaction;
    private boolean pendingBrightnessPreviewSet;
    private boolean brightnessPreviewWorkerRunning;
    private boolean brightnessPreviewUnavailable;
    private boolean attached, expanded, closing;
    private boolean chromeIntroPending;
    /** One opening choreography per shade attachment; live refreshes never replay it. */
    private boolean notificationIntroPending;
    /** Cached because sysfs is not readable by the app before the root daemon is ready. */
    private volatile int reverseCharging = -1;
    private volatile int hotspot = -1;
    private float sheetH;
    private final List<Runnable> refreshers = new ArrayList<Runnable>();   // tiles, rebuilt with them
    private final List<Runnable> fixed = new ArrayList<Runnable>();        // header and brightness row
    private final HashMap<String, CharSequence> labels = new HashMap<String, CharSequence>();
    private final List<ImageView> headerStatusIcons = new ArrayList<ImageView>();
    private final List<MorphTile> morphTiles = new ArrayList<MorphTile>();
    private AppWidgetHost homeWidgetHost;
    private AppWidgetHostView homeWidgetView;
    private TextView homeLocationLabel;
    private final HashMap<View, Boolean> homeTileStates = new HashMap<View, Boolean>();
    private int homeWidgetId;
    /** One timeline owns sheet travel, scrim, navbar tint and compositor blur. */
    private android.animation.ValueAnimator revealAnimator;
    private WindowManager.LayoutParams navBackdropLp;
    private int targetBackdropBlur;
    private int appliedBackdropBlur = -1;
    private int pendingBackdropBlur;
    private boolean backdropBlurUpdatePosted;
    private int backdropGeneration;
    private float revealProgress;
    private float expansionProgress;
    private LayoutTransition expansionSavedTransition;
    private LayoutTransition contentExpansionSavedTransition;
    private android.animation.ValueAnimator expansionAnimator;

    private static final PathInterpolator OPEN_EASE = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final PathInterpolator CLOSE_EASE = new PathInterpolator(0.4f, 0f, 1f, 1f);
    private static final PathInterpolator EMPHASIZED_EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private CameraManager cams;
    private String torchId;
    private boolean torchOn;
    private final CameraManager.TorchCallback torchCb = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean on) {
            if (id.equals(torchId)) { torchOn = on; refreshTiles(); }
        }
    };

    UnifiedShade(ShadeService svc) { this.svc = svc; }

    boolean isOpen() { return attached && !closing; }

    // ------------------------------------------------------------ open / drag / close

    /** Opened from a shortcut: slide all the way down. */
    void openAnimated() {
        if (!attach()) return;
        setRevealProgress(0f);
        settle(true, 0);
    }

    /** A pull from the status bar starts: the sheet follows the finger from here. */
    void beginDrag() {
        if (attach()) setRevealProgress(0f);
    }

    void drag(float dy) {
        if (!attached || closing) return;
        float t = Math.max(-sheetH, Math.min(0, -sheetH + dy * 1.15f));
        setRevealProgress((t + sheetH) / sheetH);
    }

    void endDrag(float velocityY) {
        if (!attached || closing) return;
        float shown = sheet.getTranslationY() + sheetH;
        settle(velocityY > 800 || (velocityY > -800 && shown > sheetH * 0.18f), velocityY);
    }

    void close() { if (attached && !closing) settle(false, 0); }

    void closeNow() {
        if (!attached) return;
        detach();
    }

    private void settle(final boolean open, float velocity) {
        final float targetProgress = open ? 1f : 0f;
        float dist = Math.abs(revealProgress - targetProgress) * sheetH;
        long ms = (long) Math.max(190, Math.min(460, dist / Math.max(1300, Math.abs(velocity)) * 1000));
        if (!open) closing = true;
        sheet.animate().cancel();
        if (scrim != null) scrim.animate().cancel();
        if (navBackdrop != null) navBackdrop.animate().cancel();
        if (revealAnimator != null) revealAnimator.cancel();
        // The chrome belongs to the sheet. Start any one-shot colour handoff with the
        // sheet itself instead of waiting for the panel to land and making its controls
        // look as though they were inflated late.
        if (open) runChromeIntroIfNeeded();
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(
                revealProgress, targetProgress);
        revealAnimator = a;
        a.setDuration(ms);
        a.setInterpolator(open ? OPEN_EASE : CLOSE_EASE);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                setRevealProgress((Float) animation.getAnimatedValue());
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (revealAnimator == animation) revealAnimator = null;
                if (!cancelled && !open) detach();
            }
        });
        a.start();
    }

    private void setRevealProgress(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        revealProgress = p;
        sheet.setTranslationY(-sheetH * (1f - p));
        float scale = 0.985f + 0.015f * p;
        sheet.setScaleX(scale);
        sheet.setScaleY(scale);
        if (scrim != null) scrim.setAlpha(p);
        applyBackdropProgress(p);
        if (chromeIntroPending) {
            applyAccessoryReveal(homeShelfView, (p - 0.12f) / 0.58f, svc.px(18));
            float mediaProgress = (p - 0.22f) / 0.58f;
            for (int i = 0; i < mediaIntroViews.size(); i++)
                applyAccessoryReveal(mediaIntroViews.get(i), mediaProgress - i * 0.05f, svc.px(22));
        }
    }

    private boolean attach() {
        if (attached) {
            closing = false;
            // Cancelling a closing ViewPropertyAnimator invokes its old onAnimationEnd.
            // Remove that listener first or a fast reopen immediately detaches the shade.
            sheet.animate().setListener(null);
            sheet.animate().cancel();
            if (scrim != null) scrim.animate().cancel();
            if (revealAnimator != null) {
                revealAnimator.cancel();
                revealAnimator = null;
            }
            setRevealProgress(1f);
            return true;
        }
        pal = Palette.of(svc, Prefs.n(svc, Prefs.SHADE_THEME));
        int ai = Prefs.n(svc, Prefs.SHADE_ACCENT);
        if (ai > 0 && ai < ACCENTS.length) pal = pal.withAccent(ACCENTS[ai]);
        pal = pal.withShadeColors(
                Prefs.n(svc, Prefs.SHADE_BG_COLOR),
                Prefs.n(svc, Prefs.SHADE_PANEL_COLOR),
                Prefs.n(svc, Prefs.SHADE_INACTIVE_COLOR),
                Prefs.n(svc, Prefs.SHADE_ACTIVE_COLOR),
                Prefs.n(svc, Prefs.SHADE_TEXT_COLOR),
                Prefs.n(svc, Prefs.SHADE_SECONDARY_COLOR));
        int notificationColor = Prefs.n(svc, Prefs.SHADE_NOTIFICATION_TEXT_COLOR);
        // Message bodies are primary readable content, not metadata. Keep their default tied
        // to primary foreground (normally white in the dark shade) and let the dedicated
        // Notification text override remain completely independent of secondary text.
        notificationTextColor = notificationColor == 0 ? pal.onSurface : notificationColor | 0xFF000000;
        panelA = Math.round(255 * Math.max(40, Math.min(100, Prefs.n(svc, Prefs.SHADE_ALPHA))) / 100f);
        build();
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        // the shade ends at the nav bar, so Back / Home / Recents stay visible and usable
        int height = shadeHeight();
        sheetH = height;
        // Establish the hidden geometry before either window is submitted. Doing this after
        // addView leaves one compositor frame with the scrim and sheet fully visible.
        setRevealProgress(0f);
        WindowManager.LayoutParams lp = svc.overlay(WindowManager.LayoutParams.MATCH_PARENT, height, 0);
        lp.gravity = Gravity.TOP;
        // This window must own focus while open so Back is dispatched here instead of to the
        // app underneath. Its bounds still stop above NavigationBar0, so HyperOS continues to
        // draw and operate the genuine Back/Home/Recents controls in their own window.
        lp.flags = (lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) | flags;
        if (svc.locked()) lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
        // Blur is hosted by the application-overlay backdrop below SystemUI. Putting
        // FLAG_BLUR_BEHIND on this accessibility window makes SurfaceFlinger create a
        // full-display blur layer above NavigationBar0 even though our bounds stop above it,
        // which erases HyperOS's genuine Back/Home/Recents glyphs.
        lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        lp.dimAmount = 0f;
        // Keep our bounds above SystemUI's real navigation bar. The IME is handled below.
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        lp.setTitle("TickerBar shade");
        // Submit the lower application-overlay first. It can then be composed on the same
        // first frame as the accessibility sheet instead of appearing a frame or two later.
        attachNavBackdrop();
        try { svc.windows().addView(root, lp); } catch (Exception e) {
            if (navBackdrop != null) {
                try { navBackdropWm.removeViewImmediate(navBackdrop); } catch (Exception ignored) {}
                navBackdrop = null;
                navBackdropWm = null;
                navBackdropLp = null;
            }
            return false;
        }
        root.requestFocus();

        if (Build.VERSION.SDK_INT >= 33) {
            // targeting 36, back arrives as a back callback rather than a key
            backCb = new android.window.OnBackInvokedCallback() { public void onBackInvoked() { back(); } };
            android.window.OnBackInvokedDispatcher d = root.findOnBackInvokedDispatcher();
            if (d != null) d.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    (android.window.OnBackInvokedCallback) backCb);
        }
        attached = true;
        closing = false;
        setRevealProgress(0f);
        startTorchWatch();
        refreshHotspot();
        refreshReverseCharging();
        notificationIntroPending = true;
        chromeIntroPending = true;
        refreshAll();
        prepareChromeIntro();
        return true;
    }

    private Object backCb;
    private int imeHeight;

    /**
     * Full-display frosted backdrop below SystemUI, with the shade tint in the navbar band.
     * Keeping blur on this APPLICATION_OVERLAY layer means NavigationBar0 and its genuine
     * controls are composed afterward and remain crisp. The entire window is non-touchable.
     */
    private void attachNavBackdrop() {
        if (svc.navBar() <= 0 || navBackdrop != null) return;
        backdropGeneration++;
        FrameLayout backdrop = new FrameLayout(svc);
        backdrop.setBackgroundColor(Color.TRANSPARENT);
        backdrop.setAlpha(0f);
        View navTint = new View(svc);
        int desiredTintAlpha = pal.dark ? 0x88 : 0x99;
        // Android caps TYPE_APPLICATION_OVERLAY windows at 0.8 opacity. Without compensating
        // the child tint, the navbar band receives only 80% of the scrim colour and is visibly
        // lighter than the shade. The resulting on-screen alpha now matches the main scrim.
        float overlayCap = Settings.Global.getFloat(svc.getContentResolver(),
                "maximum_obscuring_opacity_for_touch", 0.8f);
        overlayCap = Math.max(0.1f, Math.min(1f, overlayCap));
        int compensatedTintAlpha = Math.min(255, Math.round(desiredTintAlpha / overlayCap));
        navTint.setBackgroundColor(Palette.alpha(
                pal.dark ? Color.BLACK : pal.bg, compensatedTintAlpha));
        FrameLayout.LayoutParams tintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, svc.navBar(), Gravity.BOTTOM);
        backdrop.addView(navTint, tintLp);
        navBackdrop = backdrop;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        int blur = Math.max(0, Math.min(120, Prefs.n(svc, Prefs.SHADE_BLUR)));
        targetBackdropBlur = svc.px(blur);
        appliedBackdropBlur = -1;
        if (Build.VERSION.SDK_INT >= 31 && targetBackdropBlur > 0)
            flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        if (Build.VERSION.SDK_INT >= 30) lp.setFitInsetsTypes(0);
        // A view alpha does not attenuate FLAG_BLUR_BEHIND. Start the compositor at zero
        // and drive the real radius from setRevealProgress, otherwise the wallpaper jumps
        // to fully blurred on the first attached frame while the shade is still off-screen.
        if (Build.VERSION.SDK_INT >= 31 && targetBackdropBlur > 0) lp.setBlurBehindRadius(0);
        lp.dimAmount = 0f;
        lp.setTitle("TickerBar shade backdrop");
        navBackdropLp = lp;
        try {
            // The service WindowManager supplies its accessibility token even for an explicit
            // TYPE_APPLICATION_OVERLAY, re-parenting the surface above NavigationBar0. The
            // application context has no accessibility token, so WMS gives this its proper
            // below-SystemUI application-overlay layer.
            navBackdropWm = svc.applicationWindows();
            navBackdropWm.addView(navBackdrop, lp);
        } catch (Exception ignored) {
            navBackdrop = null;
            navBackdropWm = null;
            navBackdropLp = null;
        }
    }

    /** Fade and blur are perceptually eased together, including direct finger tracking. */
    private void applyBackdropProgress(float progress) {
        if (navBackdrop == null) return;
        float p = Math.max(0f, Math.min(1f, progress));
        // Use the exact same reveal value as the main scrim. A second easing curve makes the
        // navigation band visibly lighter/darker than the rest of the shade during a pull.
        navBackdrop.setAlpha(p);
        if (Build.VERSION.SDK_INT < 31 || navBackdropLp == null || navBackdropWm == null
                || targetBackdropBlur <= 0) return;
        int radius = Math.round(targetBackdropBlur * p);
        // Updating WMS for identical one-pixel steps wastes compositor work on high-density
        // displays. Two-pixel quantisation is visually continuous and keeps pull-down at 60fps.
        if (radius != 0 && radius != targetBackdropBlur && appliedBackdropBlur >= 0
                && Math.abs(radius - appliedBackdropBlur) < 2) return;
        if (radius == appliedBackdropBlur && !backdropBlurUpdatePosted) return;
        pendingBackdropBlur = radius;
        if (backdropBlurUpdatePosted) return;
        backdropBlurUpdatePosted = true;
        final int generation = backdropGeneration;
        // Direct touch can deliver more samples than the display can present. Coalesce them
        // to one WMS transaction per frame so the lower overlay never churns the real navbar.
        navBackdrop.postOnAnimation(new Runnable() { public void run() {
            if (generation != backdropGeneration) return;
            backdropBlurUpdatePosted = false;
            if (navBackdrop == null || navBackdropLp == null || navBackdropWm == null) return;
            int next = pendingBackdropBlur;
            if (next == appliedBackdropBlur) return;
            appliedBackdropBlur = next;
            navBackdropLp.setBlurBehindRadius(next);
            try { navBackdropWm.updateViewLayout(navBackdrop, navBackdropLp); }
            catch (Exception ignored) {}
        }});
    }

    private void setReplyFocusable(boolean focusable) {
        if (!attached || root == null) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
            // The shade remains the focused window after an inline reply closes; otherwise
            // the next Back press falls through to the app behind it.
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            svc.windows().updateViewLayout(root, lp);
            if (!focusable) root.requestFocus();
        } catch (Exception ignored) {}
    }

    /**
     * Keep the field being typed in visible when the keyboard opens. SystemUI continues
     * to own and draw the real navigation bar below this window.
     */
    private void onImeChanged(int ime) {
        if (ime == imeHeight) return;
        imeHeight = ime;
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(),
                svc.px(28) + ime);
        if (ime > 0) scroll.postDelayed(new Runnable() { public void run() {
            if (!attached || root == null || scroll == null || imeHeight <= 0) return;
            View f = root.findFocus();
            // Focus can return to the shade root while this delayed IME callback is queued,
            // or the reply editor can be replaced during a notification refresh. ViewGroup
            // throws (and kills the Accessibility service) if asked to offset a view outside
            // this ScrollView, so validate the relationship before doing any geometry.
            if (f == null || !isDescendantOf(f, scroll)) return;
            android.graphics.Rect r = new android.graphics.Rect();
            f.getDrawingRect(r);
            scroll.offsetDescendantRectToMyCoords(f, r);
            int visibleBottom = scroll.getScrollY() + scroll.getHeight() - imeHeight - svc.navBar() - svc.px(12);
            if (r.bottom > visibleBottom) scroll.smoothScrollBy(0, r.bottom - visibleBottom);
        }}, 120);
    }

    /** Leave the SystemUI navigation-bar region completely uncovered. */
    private int shadeHeight() { return Math.max(svc.px(320), svc.screenH() - svc.navBar()); }

    /** Back dismisses an active reply first; otherwise it closes the shade in one press. */
    private void back() {
        // Once this focusable overlay owns Back, SystemUI no longer supplies the navbar's
        // usual click feedback. VIRTUAL_KEY restores it while still honoring the user's
        // system-level touch-feedback setting.
        performBackHaptic();
        if (replying && replyClose != null) { replyClose.run(); return; }
        close();
    }

    /** HyperOS reports View haptics as accepted without always driving the motor. */
    private void performBackHaptic() {
        if (Settings.System.getInt(svc.getContentResolver(),
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
        try {
            android.os.Vibrator vibrator;
            if (Build.VERSION.SDK_INT >= 31) {
                android.os.VibratorManager manager = (android.os.VibratorManager)
                        svc.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = (android.os.Vibrator) svc.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return;
            // HyperOS uses HEAVY_CLICK for the three-button navigation bar. Its ordinary
            // CLICK preset is much softer on this device and can feel like no feedback at all.
            android.os.VibrationEffect effect = Build.VERSION.SDK_INT >= 29
                    ? android.os.VibrationEffect.createPredefined(
                            android.os.VibrationEffect.EFFECT_HEAVY_CLICK)
                    : android.os.VibrationEffect.createOneShot(22,
                            android.os.VibrationEffect.DEFAULT_AMPLITUDE);
            android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            vibrator.vibrate(effect, attributes);
        } catch (Exception ignored) {}
    }

    private void detach() {
        // A close/cancel must never leave DisplayManager's temporary drag override behind.
        endBrightnessPreview(false);
        // Listeners refer to this instance's mutable view fields. Stop callbacks from this
        // attachment before build() can bind those fields to a newly opened shade.
        if (sheet != null) {
            sheet.animate().setListener(null);
            sheet.animate().cancel();
        }
        if (revealAnimator != null) {
            revealAnimator.cancel();
            revealAnimator = null;
        }
        if (scrim != null) scrim.animate().cancel();
        if (expansionAnimator != null) {
            expansionAnimator.cancel();
            expansionAnimator = null;
        }
        expansionSavedTransition = null;
        contentExpansionSavedTransition = null;
        expansionProgress = 0f;
        expanded = false;
        stopTorchWatch();
        stopHomeWidgetHost();
        if (Build.VERSION.SDK_INT >= 33 && backCb != null && root != null) {
            android.window.OnBackInvokedDispatcher d = root.findOnBackInvokedDispatcher();
            if (d != null) try { d.unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCb); }
            catch (Exception ignored) {}
            backCb = null;
        }
        replyClose = null;
        imeHeight = 0;
        if (navBackdrop != null) {
            backdropGeneration++;
            try { navBackdropWm.removeViewImmediate(navBackdrop); } catch (Exception ignored) {}
            navBackdrop = null;
            navBackdropWm = null;
            navBackdropLp = null;
        }
        if (attached) {
            try { svc.windows().removeViewImmediate(root); } catch (Exception ignored) {}
        }
        attached = false;
        closing = false;
        revealProgress = 0f;
        targetBackdropBlur = 0;
        appliedBackdropBlur = -1;
        pendingBackdropBlur = 0;
        backdropBlurUpdatePosted = false;
        refreshers.clear();
        fixed.clear();
        mediaIntroViews.clear();
        homeTileStates.clear();
        groupOpenState.clear();
        cardOpenState.clear();
        swipingCard = false;
        replying = false;
        for (Player p : players.values()) p.release();
        players.clear();
        if (root != null) root.removeCallbacks(mediaTick);
        svc.shadeClosed();
    }

    // ------------------------------------------------------------ layout

    private void build() {
        refreshers.clear();
        fixed.clear();
        headerStatusIcons.clear();
        brightnessLevelQueried = false;
        root = new FrameLayout(svc) {
            private float y0, x0;
            private boolean dragging, pulledOpen;
            private VelocityTracker vt;

            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) back();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }

            // swipe up anywhere (once the list can't scroll further) puts the shade away
            @Override public boolean onInterceptTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        y0 = e.getRawY(); x0 = e.getRawX(); dragging = false; pulledOpen = false;
                        if (vt != null) vt.recycle();
                        vt = VelocityTracker.obtain();
                        vt.addMovement(e);
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        if (vt != null) vt.addMovement(e);
                        float up = y0 - e.getRawY();
                        int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
                        if (up > slop * 2 && up > Math.abs(e.getRawX() - x0)) {
                            // An upward pull that started on an open notification folds that
                            // entry first. Previously the root interceptor stole it for the
                            // whole shade/control panel, so only the tiny chevron worked.
                            View under = entryAt(x0, y0);
                            Runnable collapse = collapsers.get(under);
                            if (collapse != null) {
                                pulledOpen = true;
                                collapse.run();
                                return true;
                            }
                            if (!scroll.canScrollVertically(1)) {
                                if (expanded) {   // stock: the first swipe up folds the controls, the next closes
                                    pulledOpen = true;
                                    setExpanded(false);
                                    return true;
                                }
                                dragging = true;
                                return true;
                            }
                        }
                        // pulling down: on a notification expands that one; elsewhere (empty space,
                        // with the list at its top) opens the full quick settings
                        if (-up > svc.px(32) && -up > Math.abs(e.getRawX() - x0) * 1.2f) {
                            View under = entryAt(x0, y0);
                            if (under != null) {
                                Runnable ex = expanders.get(under);
                                if (ex == null) return false;   // nothing more to show; leave it be
                                pulledOpen = true;
                                ex.run();
                                return true;
                            }
                            if (!expanded && !scroll.canScrollVertically(-1)) {
                                pulledOpen = true;
                                setExpanded(true);
                                return true;
                            }
                        }
                        return false;
                    }
                    default:
                        return false;
                }
            }

            @Override public boolean onTouchEvent(MotionEvent e) {
                if (vt != null) vt.addMovement(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        y0 = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (dragging) {
                            float t = Math.max(-sheetH, Math.min(0, e.getRawY() - y0));
                            setRevealProgress((t + sheetH) / sheetH);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float v = 0;
                        if (vt != null) { vt.computeCurrentVelocity(1000); v = vt.getYVelocity(); vt.recycle(); vt = null; }
                        if (pulledOpen) {
                            pulledOpen = false;   // that pull expanded the panel; nothing more to do
                        } else if (dragging) {
                            dragging = false;
                            settle(!(v < -600 || -sheet.getTranslationY() > sheet.getHeight() * 0.2f), v);
                        } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                            close();   // a tap on the empty backdrop
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            }
        };
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setFocusableInTouchMode(true);
        scrim = new View(svc);
        scrim.setBackgroundColor(Palette.alpha(pal.dark ? Color.BLACK : pal.bg, pal.dark ? 0x88 : 0x99));
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        sheet = new LinearLayout(svc);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPivotX(svc.getResources().getDisplayMetrics().widthPixels / 2f);
        sheet.setPivotY(0f);
        root.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                    onImeChanged(in.isVisible(android.view.WindowInsets.Type.ime())
                            ? in.getInsets(android.view.WindowInsets.Type.ime()).bottom : 0);
                    return in;
                }
            });
        }

        scroll = new ScrollView(svc);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(svc.px(18));
        scroll.setClipToPadding(false);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(svc);
        content.setOrientation(LinearLayout.VERTICAL);
        // Starts at the very top: the panel's top corners follow the screen's own curve
        int corner = svc.screenCorner();
        int side = svc.px(8);
        content.setPadding(side, side, side, svc.px(28));
        content.setLayoutTransition(polishedTransition());
        // empty space (below the list, between cards) puts the shade away, like stock
        content.setMinimumHeight(shadeHeight());   // exactly the window, so the panel can't scroll by the nav bar
        content.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { close(); } });
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- quick settings panel
        LinearLayout qs = new LinearLayout(svc);
        quickPanel = qs;
        qs.setOrientation(LinearLayout.VERTICAL);
        int topR = Math.max(svc.px(32), corner - side);
        // keep the header clear of the corner curve, and of a camera cut-out in the middle
        qs.setPadding(svc.px(12), Math.max(svc.px(14), topR / 3), svc.px(12), svc.px(12));
        GradientDrawable qsBg = new GradientDrawable();
        qsBg.setColor(Palette.alpha(pal.surface, panelA));
        qsBg.setCornerRadii(new float[]{topR, topR, topR, topR, svc.px(32), svc.px(32), svc.px(32), svc.px(32)});
        qs.setBackground(qsBg);
        qs.setLayoutTransition(polishedTransition());
        qs.setClickable(true);   // taps inside the panel never fall through to "close"
        content.addView(qs);

        View head = header();
        headerView = head;
        head.setPadding(svc.px(4) + topR / 4, 0, svc.px(4) + topR / 4, 0);   // clear of the screen's corner curve
        qs.addView(head);

        // One physical set of tiles serves both states. Shared tiles move and reshape in
        // place; expanded-only tiles emerge between them instead of swapping in a new grid.
        tileStage = new FrameLayout(svc);
        tileStage.setClipChildren(true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, svc.px(50));
        tlp.topMargin = svc.px(14);
        qs.addView(tileStage, tlp);

        qs.addView(brightnessRow());

        chevron = new ImageView(svc);
        chevron.setImageResource(R.drawable.qs_chevron);
        chevron.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        chevron.setPadding(svc.px(8), svc.px(2), svc.px(8), svc.px(2));
        chevron.setBackground(ripple(null));
        chevron.setContentDescription("Expand quick settings");
        chevron.setOnTouchListener(new View.OnTouchListener() {
            float y0, startProgress;
            boolean dragging;
            VelocityTracker velocity;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        y0 = e.getRawY();
                        startProgress = expanded ? 1f : 0f;
                        expansionProgress = startProgress;
                        dragging = false;
                        if (velocity != null) velocity.recycle();
                        velocity = VelocityTracker.obtain();
                        velocity.addMovement(e);
                        prepareExpansionDrag();
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (velocity != null) velocity.addMovement(e);
                        float dy = e.getRawY() - y0;
                        if (Math.abs(dy) > ViewConfiguration.get(svc).getScaledTouchSlop()) dragging = true;
                        float direction = expanded ? -dy : dy;
                        // A compact 120dp throw makes the handle feel directly attached to the
                        // grid. The old 220dp range required nearly half a screen before a
                        // gesture crossed its settle threshold.
                        float p = expanded ? 1f - direction / svc.px(120) : direction / svc.px(120);
                        applyExpansionProgress(Math.max(0f, Math.min(1f, p)));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float vy = 0f;
                        if (velocity != null) {
                            velocity.addMovement(e);
                            velocity.computeCurrentVelocity(1000);
                            vy = velocity.getYVelocity();
                            velocity.recycle();
                            velocity = null;
                        }
                        if (!dragging && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            settleExpansionProgress(!expanded);
                        } else if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            settleExpansionProgress(expansionProgress >= 0.5f);
                        } else if (vy > 350f) {
                            settleExpansionProgress(true);
                        } else if (vy < -350f) {
                            settleExpansionProgress(false);
                        } else {
                            settleExpansionProgress(expansionProgress >= 0.35f);
                        }
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                        return true;
                    }
                    default: return true;
                }
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(svc.px(56), svc.px(26));
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        clp.topMargin = svc.px(4);
        qs.addView(chevron, clp);

        if (Prefs.on(svc, Prefs.HOME_SHELF)) {
            homeShelfView = homeShelf();
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = svc.px(12);
            content.addView(homeShelfView, hlp);
        } else homeShelfView = null;

        // ---- notifications
        notifBox = new LinearLayout(svc);
        notifBox.setOrientation(LinearLayout.VERTICAL);
        // Notification refreshes use one explicit fade + bounds transition. LayoutTransition
        // and TransitionManager fighting over the same children was the source of occasional
        // flashes and one-frame jumps when a section header appeared or disappeared.
        notifBox.setLayoutTransition(null);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = svc.px(12);
        content.addView(notifBox, nlp);

        expanded = false;
    }

    private View header() {
        LinearLayout h = new LinearLayout(svc);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout time = new LinearLayout(svc);
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.START);
        TextClock clock = new TextClock(svc);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        clock.setTextColor(pal.onSurface);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setGravity(Gravity.START);
        time.addView(clock, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextClock date = new TextClock(svc);
        date.setFormat12Hour("EEE, MMM d");
        date.setFormat24Hour("EEE, MMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        date.setTextColor(pal.onSurfaceVariant);
        date.setGravity(Gravity.START);
        date.setPadding(0, -svc.px(2), 0, 0);
        time.addView(date, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tlp.setMarginEnd(svc.px(8));
        h.addView(time, tlp);

        final ImageView wifiStatus = statusIcon(R.drawable.qs_wifi, "Wi-Fi on");
        final ImageView dataStatus = statusIcon(R.drawable.qs_data, "Mobile data on");
        final ImageView bluetoothStatus = statusIcon(R.drawable.qs_bluetooth, "Bluetooth on");
        final ImageView quietStatus = statusIcon(R.drawable.qs_dnd, "Do Not Disturb or silent mode on");
        LinearLayout indicators = new LinearLayout(svc);
        indicators.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        for (ImageView icon : new ImageView[]{wifiStatus, dataStatus, bluetoothStatus, quietStatus}) {
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(svc.px(15), svc.px(15));
            ilp.setMarginStart(svc.px(1));
            ilp.setMarginEnd(svc.px(1));
            indicators.addView(icon, ilp);
        }
        fixed.add(new Runnable() { public void run() {
            boolean wifi = false, bluetooth = false;
            try { wifi = ((WifiManager) svc.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).isWifiEnabled(); }
            catch (Exception ignored) {}
            try {
                android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager)
                        svc.getSystemService(Context.BLUETOOTH_SERVICE);
                bluetooth = bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
            } catch (Exception ignored) {
                bluetooth = Settings.Global.getInt(svc.getContentResolver(), "bluetooth_on", 0) == 1;
            }
            AudioManager audio = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
            NotificationManager nm = (NotificationManager) svc.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean quiet = (audio != null && audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL)
                    || (nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL);
            showStatusIcon(wifiStatus, wifi);
            showStatusIcon(dataStatus,
                    Settings.Global.getInt(svc.getContentResolver(), "mobile_data", 1) == 1);
            showStatusIcon(bluetoothStatus, bluetooth);
            showStatusIcon(quietStatus, quiet);
        }});

        LinearLayout utilities = new LinearLayout(svc);
        utilities.setOrientation(LinearLayout.VERTICAL);
        utilities.setGravity(Gravity.END);
        LinearLayout utilityButtons = new LinearLayout(svc);
        utilityButtons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        final BatteryMeter batt = new BatteryMeter();
        int batteryStyle = SystemBarSettings.getInt(svc, "system",
                "battery_indicator_style", 1);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                svc.px(batteryStyle == 0 ? 28 : batteryStyle == 2 ? 24 : 20),
                svc.px(10));
        blp.setMarginStart(svc.px(4));
        indicators.addView(batt, blp);
        utilities.addView(indicators, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                svc.px(17)));
        fixed.add(new Runnable() { public void run() {
            Intent b = svc.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int st = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0);
            boolean charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
            if (level >= 0) batt.setLevel(Math.round(100f * level / scale), charging);
        }});

        ImageView power = new ImageView(svc);
        power.setImageResource(R.drawable.qs_power);
        power.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        power.setPadding(svc.px(8), svc.px(8), svc.px(8), svc.px(8));
        power.setBackground(ripple(round(pal.surfaceHigh, svc.px(16))));
        power.setContentDescription("Power menu");
        power.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            closeNow();
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        }});
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(svc.px(32), svc.px(32));
        plp.setMarginEnd(svc.px(4));
        utilityButtons.addView(power, plp);

        ImageView gear = new ImageView(svc);
        gear.setImageResource(R.drawable.qs_settings);
        gear.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        gear.setPadding(svc.px(8), svc.px(8), svc.px(8), svc.px(8));
        gear.setBackground(ripple(round(pal.surfaceHigh, svc.px(16))));
        gear.setContentDescription("Settings");
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            launch(new Intent(Settings.ACTION_SETTINGS)); }});
        utilityButtons.addView(gear, new LinearLayout.LayoutParams(svc.px(32), svc.px(32)));
        utilities.addView(utilityButtons);
        h.addView(utilities, new LinearLayout.LayoutParams(svc.px(94), ViewGroup.LayoutParams.WRAP_CONTENT));
        return h;
    }

    private ImageView statusIcon(int drawable, String description) {
        ImageView icon = new ImageView(svc);
        icon.setImageResource(drawable);
        icon.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setContentDescription(description);
        headerStatusIcons.add(icon);
        return icon;
    }

    private void showStatusIcon(final ImageView icon, boolean show) {
        icon.animate().cancel();
        if (show) {
            if (icon.getVisibility() != View.VISIBLE || icon.getAlpha() < 1f) {
                icon.setVisibility(View.VISIBLE);
                icon.setAlpha(0f);
                icon.setScaleX(0.76f);
                icon.setScaleY(0.76f);
                icon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(OPEN_EASE).start();
            }
        } else if (icon.getVisibility() == View.VISIBLE) {
            icon.animate().alpha(0f).scaleX(0.76f).scaleY(0.76f).setDuration(150)
                    .setInterpolator(CLOSE_EASE).withEndAction(new Runnable() { public void run() {
                        icon.setVisibility(View.GONE);
                    }}).start();
        }
    }

    /** Mirrors the battery style selected for HyperOS's real status bar. */
    private final class BatteryMeter extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF body = new RectF();
        private final int style = SystemBarSettings.getInt(svc, "system",
                "battery_indicator_style", 1);
        private final boolean showPercent = SystemBarSettings.getInt(svc, "system",
                "status_bar_show_battery_percent", 1) != 0;
        private int level = 0;
        private boolean charging;

        BatteryMeter() {
            super(svc);
            setContentDescription("Battery");
        }

        void setLevel(int value, boolean isCharging) {
            level = Math.max(0, Math.min(100, value));
            charging = isCharging;
            setContentDescription("Battery " + level + " percent" + (charging ? ", charging" : ""));
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int color = charging ? pal.primary : level <= 20 ? pal.bad : pal.onSurfaceVariant;
            if (style == 2) { drawTopBar(c, color); return; }

            float cap = svc.px(1.5f);
            float iconWidth = style == 0 && showPercent ? svc.px(16) : getWidth();
            body.set(0, 0, iconWidth - cap - svc.px(1), getHeight());
            float radius = svc.px(2.5f);
            if (style == 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(svc.px(1f));
                p.setColor(color);
                c.drawRoundRect(body, radius, radius, p);
                p.setStyle(Paint.Style.FILL);
                RectF fill = new RectF(body.left + svc.px(1.5f), body.top + svc.px(1.5f),
                        body.left + svc.px(1.5f) + Math.max(0f,
                                (body.width() - svc.px(3f)) * level / 100f),
                        body.bottom - svc.px(1.5f));
                c.drawRoundRect(fill, svc.px(1.3f), svc.px(1.3f), p);
            } else {
                p.setStyle(Paint.Style.FILL);
                p.setColor(color);
                c.drawRoundRect(body, radius, radius, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawRoundRect(new RectF(body.right + svc.px(.7f), getHeight() * .33f,
                    body.right + cap + svc.px(1), getHeight() * .67f),
                    svc.px(.7f), svc.px(.7f), p);

            if (style == 0 && !showPercent) return;
            p.setColor(style == 0 ? color
                    : Palette.luminance(color) > 0.48 ? 0xFF111111 : 0xFFFFFFFF);
            p.setTextAlign(style == 0 ? Paint.Align.RIGHT : Paint.Align.CENTER);
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    style == 0 ? 6.5f : 5.5f,
                    svc.getResources().getDisplayMetrics()));
            Paint.FontMetrics fm = p.getFontMetrics();
            float y = body.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(style == 0 ? level + "%" : String.valueOf(level),
                    style == 0 ? getWidth() : body.centerX(), y, p);
        }

        private void drawTopBar(Canvas c, int color) {
            float barHeight = svc.px(2f);
            float top = (getHeight() - barHeight) / 2f;
            body.set(0, top, getWidth(), top + barHeight);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Palette.alpha(color, 0x35));
            c.drawRoundRect(body, barHeight / 2f, barHeight / 2f, p);
            p.setColor(color);
            body.right = body.left + body.width() * level / 100f;
            c.drawRoundRect(body, barHeight / 2f, barHeight / 2f, p);
        }
    }

    private View brightnessRow() {
        LinearLayout r = new LinearLayout(svc);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(svc.px(16), 0, svc.px(8), 0);
        r.setBackground(round(pal.surfaceHigh, svc.px(26)));

        // the sun is the auto-brightness switch: it turns into an "A" and lights up when auto
        final ImageView sun = new ImageView(svc);
        sun.setPadding(svc.px(7), svc.px(7), svc.px(7), svc.px(7));
        sun.setContentDescription("Auto brightness");
        sun.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (!Settings.System.canWrite(svc)) { askWriteSettings(); return; }
            try {
                Settings.System.putInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        autoBrightness() ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                                : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            } catch (Exception ignored) {}
            press(v);
            refreshTiles();
        }});
        r.addView(sun, new LinearLayout.LayoutParams(svc.px(38), svc.px(38)));
        final Boolean[] shownAuto = {null};
        fixed.add(new Runnable() { public void run() {
            final boolean auto = autoBrightness();
            if (shownAuto[0] != null && shownAuto[0] == auto) return;
            Runnable mutation = new Runnable() { public void run() {
                sun.setImageResource(auto ? R.drawable.qs_brightness_auto : R.drawable.qs_brightness);
                sun.setImageTintList(ColorStateList.valueOf(auto ? pal.onPrimary : pal.primary));
                sun.setBackground(auto ? round(pal.primary, svc.px(19)) : null);
            }};
            if (shownAuto[0] == null) mutation.run(); else crossfadeMutation(sun, mutation);
            shownAuto[0] = auto;
        }});

        brightness = new SeekBar(svc);
        brightness.setMax(1000);
        brightness.setContentDescription("Brightness");
        brightness.setProgressTintList(ColorStateList.valueOf(pal.primary));
        brightness.setThumbTintList(ColorStateList.valueOf(pal.primary));
        brightness.setProgressBackgroundTintList(ColorStateList.valueOf(pal.outline));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) applyBrightness(p, false);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) { applyBrightness(s.getProgress(), true); }
        });
        // HyperOS's accessibility-overlay dispatch never marks this SeekBar's drag as a
        // from-user progress change. Own the horizontal gesture explicitly so the thumb and
        // native adaptive-brightness level follow the finger on every build.
        brightness.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                        && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL)
                    return false;
                if (action == MotionEvent.ACTION_DOWN) {
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    beginBrightnessPreview();
                }
                if (action != MotionEvent.ACTION_CANCEL) {
                    int left = brightness.getPaddingLeft(), right = brightness.getPaddingRight();
                    float usable = Math.max(1, brightness.getWidth() - left - right);
                    int p = Math.max(0, Math.min(1000,
                            Math.round((event.getX() - left) * 1000f / usable)));
                    brightness.setProgress(p);
                    applyBrightness(p, action == MotionEvent.ACTION_UP);
                }
                if (action == MotionEvent.ACTION_CANCEL) endBrightnessPreview(false);
                return true;
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, svc.px(52), 1);
        r.addView(brightness, sp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                svc.px(52));
        lp.topMargin = svc.px(14);
        r.setLayoutParams(lp);
        fixed.add(new Runnable() { public void run() {
            try {
                if (autoBrightness()) {
                    int p = Math.max(0, Math.min(1000,
                            Prefs.n(svc, Prefs.AUTO_BRIGHTNESS_LEVEL)));
                    if (!brightnessPreviewActive) brightness.setProgress(p);
                    brightness.setContentDescription("Adaptive brightness "
                            + Math.round(p / 10f) + " percent");
                    queryNativeBrightnessRange();
                } else {
                    int p = toSlider(Settings.System.getInt(svc.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS));
                    brightness.setProgress(p);
                    brightness.setContentDescription("Brightness " + Math.round(p / 10f) + " percent");
                }
            } catch (Exception ignored) {}
        }});
        return r;
    }

    /**
     * HyperOS does not drive its brightness slider from screen_auto_brightness_adj. Its own
     * SystemUI sends absolute display levels through IDisplayManager while leaving automatic
     * mode enabled; the permanent call becomes the automatic controller's new user anchor.
     */
    private void applyBrightness(int progress, boolean commit) {
        if (!Settings.System.canWrite(svc) && !RootShell.available()) {
            if (commit) askWriteSettings();
            return;
        }
        if (autoBrightness()) {
            int p = Math.max(0, Math.min(1000, progress));
            float level = autoLevelForSlider(p);
            brightness.setContentDescription("Adaptive brightness "
                    + Math.round(p / 10f) + " percent");
            if (commit) {
                Prefs.put(svc, Prefs.AUTO_BRIGHTNESS_LEVEL, p);
                brightnessPreviewActive = false;
                if (RootShell.available()) queueBrightnessCall(level, 41);
                else try {
                    Settings.System.putInt(svc.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, Math.round(level * 255));
                } catch (Exception ignored) {}
            } else if (brightnessPreviewActive) {
                queueBrightnessCall(level, 40);
            }
        } else {
            int level = fromSlider(progress);
            try { Settings.System.putInt(svc.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, level); } catch (Exception ignored) {}
            brightness.setContentDescription("Brightness " + Math.round(progress / 10f) + " percent");
            if (commit && RootShell.available())
                Shell.run("settings put system screen_brightness " + level, null);
        }
    }

    private void beginBrightnessPreview() {
        brightnessPreviewActive = autoBrightness();
    }

    /** Match SystemUI's perceptual slider while using the live normal-mode display bounds. */
    private float autoLevelForSlider(int progress) {
        float gamma = Math.max(0f, Math.min(1f, progress / 1000f));
        float linear = (float) Math.pow(gamma, 2.2);
        return brightnessRangeMin + (brightnessRangeMax - brightnessRangeMin) * linear;
    }

    private int autoSliderForLevel(float level) {
        float span = Math.max(0.0001f, brightnessRangeMax - brightnessRangeMin);
        float linear = Math.max(0f, Math.min(1f, (level - brightnessRangeMin) / span));
        return Math.max(0, Math.min(1000,
                Math.round((float) Math.pow(linear, 1f / 2.2f) * 1000f)));
    }

    /** Read the values SystemUI itself uses, without relying on the stale manual setting. */
    private void queryNativeBrightnessRange() {
        if (brightnessLevelQueried || !RootShell.available()) return;
        brightnessLevelQueried = true;
        Shell.runResult("dumpsys display | awk -F= '"
                + "/mCachedBrightnessInfo.brightnessMin=/{if(!a){print $2;a=1}} "
                + "/mCachedBrightnessInfo.brightnessMax=/{if(!b){print $2;b=1}} "
                + "/mCurrentScreenBrightness=/{if(!c){print $2;c=1}}'",
                new Shell.ResultCallback() { public void done(Shell.Result result) {
            if (result.code != 0) return;
            String[] lines = result.output.trim().split("\\s+");
            if (lines.length < 3) return;
            try {
                float min = Float.parseFloat(lines[0]);
                float max = Float.parseFloat(lines[1]);
                float current = Float.parseFloat(lines[2]);
                if (min >= 0f && max > min) {
                    brightnessRangeMin = min;
                    brightnessRangeMax = max;
                    int p = autoSliderForLevel(current);
                    Prefs.put(svc, Prefs.AUTO_BRIGHTNESS_LEVEL, p);
                    if (attached && autoBrightness() && !brightnessPreviewActive) {
                        brightness.setProgress(p);
                        brightness.setContentDescription("Adaptive brightness "
                                + Math.round(p / 10f) + " percent");
                    }
                }
            } catch (Exception ignored) {}
        }});
    }

    /**
     * Transaction 40 is setTemporaryBrightness; 41 is setBrightness. The latter is exactly
     * what stock SystemUI commits even in automatic mode, updating the adaptive user model
     * without switching auto off. Calls share one coalescing worker so release always lands
     * after the last preview and a fast finger cannot pile up root processes.
     */
    private void queueBrightnessCall(float value, int transaction) {
        if (brightnessPreviewUnavailable) return;
        synchronized (brightnessPreviewLock) {
            pendingBrightnessPreview = value;
            pendingBrightnessTransaction = transaction;
            pendingBrightnessPreviewSet = true;
            if (brightnessPreviewWorkerRunning) return;
            brightnessPreviewWorkerRunning = true;
        }
        new Thread(new Runnable() { public void run() {
            for (;;) {
                final float next;
                final int transaction;
                synchronized (brightnessPreviewLock) {
                    if (!pendingBrightnessPreviewSet) {
                        brightnessPreviewWorkerRunning = false;
                        return;
                    }
                    next = pendingBrightnessPreview;
                    transaction = pendingBrightnessTransaction;
                    pendingBrightnessPreviewSet = false;
                }
                String value = Float.isNaN(next) ? "nan" : Float.toString(next);
                RootShell.Result result = RootShell.exec(
                        "service call display " + transaction + " i32 0 f " + value + " >/dev/null");
                if (!result.connected || result.code != 0) {
                    brightnessPreviewUnavailable = true;
                    synchronized (brightnessPreviewLock) {
                        pendingBrightnessPreviewSet = false;
                        brightnessPreviewWorkerRunning = false;
                    }
                    return;
                }
                try { Thread.sleep(45); } catch (InterruptedException ignored) {}
            }
        }}, "TickerBar-brightness-preview").start();
    }

    private void endBrightnessPreview(boolean ignored) {
        if (!brightnessPreviewActive) return;
        brightnessPreviewActive = false;
        queueBrightnessCall(Float.NaN, 40);
    }

    /** Google Home's authenticated Favorites widget, hosted directly inside the shade. */
    private View homeShelf() {
        LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        // The hosted widget already owns the complete rounded Home surface. An additional
        // TickerBar card behind it produced two differently coloured borders and dead space.
        card.setPadding(0, 0, 0, 0);
        card.setBackground(null);
        card.setClipToOutline(true);
        card.setOutlineProvider(roundOutline(svc.px(28)));

        View hosted = createHomeWidget();
        if (hosted != null) {
            card.setContentDescription("Google Home controls. Long press to choose a different home or favorites.");
            card.setOnLongClickListener(new View.OnLongClickListener() {
                public boolean onLongClick(View v) {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    configureHomeWidget();
                    return true;
                }
            });
            FrameLayout stage = new FrameLayout(svc);
            stage.addView(hosted, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    svc.px(HOME_WIDGET_LOGICAL_HEIGHT_DP * HOME_WIDGET_SCALE)));

            homeLocationLabel = tv(HomeWidgetProfiles.displayName(svc, homeWidgetId), 14, pal.primary);
            homeLocationLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            homeLocationLabel.setSingleLine(true);
            homeLocationLabel.setGravity(Gravity.CENTER);
            homeLocationLabel.setPadding(svc.px(12), 0, svc.px(12), 0);
            homeLocationLabel.setBackground(ripple(round(Palette.alpha(pal.surface, 0xE6), svc.px(14))));
            homeLocationLabel.setContentDescription("Switch Google Home location");
            homeLocationLabel.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { openHomeWidgetSetup(true); }
            });
            FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, svc.px(28), Gravity.TOP | Gravity.END);
            nameLp.topMargin = svc.px(4);
            nameLp.setMarginEnd(svc.px(5));
            stage.addView(homeLocationLabel, nameLp);

            card.addView(stage, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    svc.px(HOME_WIDGET_LOGICAL_HEIGHT_DP * HOME_WIDGET_SCALE)));
        } else {
            LinearLayout titleRow = new LinearLayout(svc);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = tv("Google Home", 16, pal.onSurface);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            titleRow.addView(title);
            titleRow.addView(new View(svc), new LinearLayout.LayoutParams(0, 1, 1));
            TextView open = pill("Open", new View.OnClickListener() { public void onClick(View v) {
                openHomeControls();
            }});
            titleRow.addView(open);
            card.addView(titleRow);
            LinearLayout empty = new LinearLayout(svc);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(svc.px(16), svc.px(18), svc.px(16), svc.px(18));
            empty.setBackground(round(pal.surfaceHigh, svc.px(22)));
            TextView message = tv("Choose your home and favorite lights once, then control them here.",
                    14, pal.onSurfaceVariant);
            message.setGravity(Gravity.CENTER);
            empty.addView(message);
            TextView setup = pill("Set up Google Home", new View.OnClickListener() {
                public void onClick(View v) { configureHomeWidget(); }
            });
            LinearLayout.LayoutParams setupLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            setupLp.topMargin = svc.px(10);
            setupLp.gravity = Gravity.CENTER_HORIZONTAL;
            empty.addView(setup, setupLp);
            LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            emptyLp.topMargin = svc.px(8);
            card.addView(empty, emptyLp);
        }
        return card;
    }

    private View createHomeWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(svc);
        if (manager == null) return null;
        try {
            svc.getPackageManager().getReceiverInfo(HOME_WIDGET_PROVIDER, 0);
        } catch (Exception missing) {
            android.util.Log.w("TickerBarHome", "Google Home Favorites provider is unavailable", missing);
            return null;
        }

        homeWidgetHost = new AppWidgetHost(svc, HOME_WIDGET_HOST_ID);
        try { homeWidgetHost.startListening(); }
        catch (Exception denied) {
            android.util.Log.w("TickerBarHome", "Could not start widget host", denied);
            homeWidgetHost = null;
            return null;
        }

        int id = HomeWidgetProfiles.selectedId(svc);
        AppWidgetProviderInfo info = id > 0 ? manager.getAppWidgetInfo(id) : null;
        if (info == null || !HOME_WIDGET_PROVIDER.equals(info.provider)) {
            // A stale network profile must not delete a different home's still-valid widget.
            if (id > 0 && id == Prefs.n(svc, Prefs.HOME_WIDGET_ID))
                try { homeWidgetHost.deleteAppWidgetId(id); } catch (Exception ignored) {}
            try { id = homeWidgetHost.allocateAppWidgetId(); }
            catch (Exception denied) { stopHomeWidgetHost(); return null; }
            Bundle options = homeWidgetOptions();
            boolean bound;
            try { bound = manager.bindAppWidgetIdIfAllowed(id, HOME_WIDGET_PROVIDER, options); }
            catch (Exception denied) { bound = false; }
            if (!bound) {
                try { homeWidgetHost.deleteAppWidgetId(id); } catch (Exception ignored) {}
                stopHomeWidgetHost();
                android.util.Log.w("TickerBarHome", "Google Home widget binding was not allowed");
                return null;
            }
            Prefs.put(svc, Prefs.HOME_WIDGET_ID, id);
            android.util.Log.i("TickerBarHome", "Bound Google Home Favorites widget id=" + id);
            info = manager.getAppWidgetInfo(id);
        }
        if (info == null) return null;
        try {
            homeWidgetId = id;
            manager.updateAppWidgetOptions(id, homeWidgetOptions());
            homeWidgetView = homeWidgetHost.createView(svc, id, info);
            homeWidgetView.setAppWidget(id, info);
            homeWidgetView.setPadding(0, 0, 0, 0);
            // Give Google enough logical height to retain labels, but use its resize path
            // instead of shrinking the tall 3x2 launcher template. The live RemoteViews and
            // PendingIntents remain Google's; polishHomeWidget only adapts their presentation.
            int physicalWidth = Math.max(1, svc.screenW() - svc.px(52));
            int logicalWidth = Math.round(physicalWidth / HOME_WIDGET_SCALE);
            homeWidgetView.setPivotX(0f);
            homeWidgetView.setPivotY(0f);
            homeWidgetView.setScaleX(HOME_WIDGET_SCALE);
            homeWidgetView.setScaleY(HOME_WIDGET_SCALE);
            homeWidgetView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (right > left && bottom > top) polishHomeWidget(v);
                }
            });
            homeWidgetView.postDelayed(new Runnable() { public void run() {
                if (homeWidgetView != null) polishHomeWidget(homeWidgetView);
            }}, 350);
            homeWidgetView.postDelayed(new Runnable() { public void run() {
                if (homeWidgetView != null) polishHomeWidget(homeWidgetView);
            }}, 1200);
            FrameLayout viewport = new FrameLayout(svc);
            viewport.setClipChildren(true);
            viewport.setClipToPadding(true);
            viewport.addView(homeWidgetView, new FrameLayout.LayoutParams(
                    logicalWidth, svc.px(HOME_WIDGET_LOGICAL_HEIGHT_DP)));
            viewport.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (homeWidgetView == null || right <= left) return;
                    int wanted = Math.round((right - left) / HOME_WIDGET_SCALE);
                    ViewGroup.LayoutParams lp = homeWidgetView.getLayoutParams();
                    if (lp.width != wanted) {
                        lp.width = wanted;
                        homeWidgetView.setLayoutParams(lp);
                    }
                }
            });
            return viewport;
        } catch (Exception unavailable) {
            android.util.Log.w("TickerBarHome", "Could not inflate Google Home widget", unavailable);
            stopHomeWidgetHost();
            return null;
        }
    }

    private void stopHomeWidgetHost() {
        if (homeWidgetHost != null) try { homeWidgetHost.stopListening(); } catch (Exception ignored) {}
        homeWidgetHost = null;
        homeWidgetView = null;
        homeLocationLabel = null;
        homeWidgetId = 0;
    }

    private Bundle homeWidgetOptions() {
        Bundle options = new Bundle();
        int widthDp = Math.max(220, Math.round((svc.screenW() - svc.px(52))
                / svc.getResources().getDisplayMetrics().density / HOME_WIDGET_SCALE));
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, HOME_WIDGET_LOGICAL_HEIGHT_DP);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, HOME_WIDGET_LOGICAL_HEIGHT_DP);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN);
        return options;
    }

    /**
     * Google Home supplies the live state and PendingIntents, but its generic 3x2 widget
     * defaults to tall launcher cards. Keep those real controls while adapting their
     * presentation to a one-shade shelf: rounded compact pills and no misleading trailing
     * detail arrow. Google's own header remains the single source of the structure name.
     */
    private void polishHomeWidget(View widget) {
        if (widget == null || widget.getWidth() <= 0) return;
        ArrayList<View> views = new ArrayList<View>();
        collectViews(widget, views);

        final float density = svc.getResources().getDisplayMetrics().density;
        ArrayList<ViewGroup> tiles = new ArrayList<ViewGroup>();
        ArrayList<View> largeLayers = new ArrayList<View>();
        View homeSurface = null;
        long homeSurfaceArea = -1;
        for (View view : views) {
            if (view instanceof GridView) {
                GridView grid = (GridView) view;
                grid.setGravity(Gravity.CENTER_HORIZONTAL);
                grid.setStretchMode(GridView.STRETCH_SPACING_UNIFORM);
                grid.setSelector(android.R.color.transparent);
            }
            if (view != widget && view instanceof ViewGroup && !view.hasOnClickListeners()
                    && view.getWidth() > widget.getWidth() * .78f
                    && view.getHeight() > widget.getHeight() * .72f) {
                largeLayers.add(view);
                long area = (long) view.getWidth() * view.getHeight();
                if (area > homeSurfaceArea) {
                    homeSurfaceArea = area;
                    homeSurface = view;
                }
            }
        }
        // RemoteViews nests several full-size containers. Styling all of them caused the
        // green inner outline plus dark outer frame seen in the screenshot. Keep one surface.
        for (View layer : largeLayers) {
            layer.setBackground(layer == homeSurface
                    ? round(Palette.alpha(pal.surfaceHigh, panelA), svc.px(28)) : null);
            if (layer == homeSurface) {
                layer.setClipToOutline(true);
                layer.setOutlineProvider(roundOutline(svc.px(28)));
            }
        }
        for (final View tile : views) {
            if (!(tile instanceof ViewGroup) || !tile.hasOnClickListeners()) continue;
            int widthDp = Math.round(tile.getWidth() / density);
            int heightDp = Math.round(tile.getHeight() / density);
            if (widthDp < 52 || widthDp > 140 || heightDp < 42 || heightDp > 125) continue;
            // A RemoteViews provider owns its control-state colors. Replacing the clicked
            // container's background also replaces Google's state-list drawable; on some
            // Home revisions that leaves an opaque white layer over the icon and labels.
            // Preserve the PendingIntent hierarchy, but give every device the same permanent
            // pill geometry. State changes now alter only colour, never shape.
            // Home first inflates the control hierarchy, then supplies live state in a later
            // RemoteViews update. Missing state is loading, not Off; treating it as Off made
            // the eventual On/Off label and active colour pop into an already-painted tile.
            final Boolean resolvedState = homeTileState((ViewGroup) tile);
            final boolean active = Boolean.TRUE.equals(resolvedState);
            // Do not call setTint on Home's state-list RippleDrawable. Its nested blend modes
            // resolve to black on this Google Home/HyperOS combination.
            final int radius = Math.round(Math.min(tile.getHeight() / 2f, svc.px(24)));
            final ViewGroup control = (ViewGroup) tile;
            Runnable restyle = new Runnable() { public void run() {
                clearHomeTileBackgroundLayers(control);
                control.setBackground(ripple(round(active ? pal.primary : pal.surfaceHigh, radius)));
                control.setClipToOutline(true);
                control.setOutlineProvider(roundOutline(radius));
                tintHomeControl(control, active);
            }};
            Boolean previousState = homeTileStates.get(tile);
            if (resolvedState != null) homeTileStates.put(tile, active);
            if (previousState != null && previousState != active) {
                crossfadeMutation(tile, restyle);
            } else if (resolvedState != null && previousState == null) {
                animateHomeStateArrival(control, active, radius);
            } else {
                restyle.run();
            }
            tiles.add((ViewGroup) tile);
        }

        HashMap<ViewGroup, Integer> rows = new HashMap<ViewGroup, Integer>();
        for (ViewGroup tile : tiles) {
            if (!(tile.getParent() instanceof ViewGroup)) continue;
            ViewGroup row = (ViewGroup) tile.getParent();
            Integer count = rows.get(row);
            rows.put(row, count == null ? 1 : count + 1);
        }
        for (java.util.Map.Entry<ViewGroup, Integer> entry : rows.entrySet()) {
            if (entry.getValue() < 2) continue;
            ViewGroup row = entry.getKey();
            if (row instanceof LinearLayout) {
                LinearLayout linear = (LinearLayout) row;
                linear.setGravity(linear.getGravity() | Gravity.CENTER_HORIZONTAL);
            } else if (row instanceof GridView) {
                ((GridView) row).setGravity(Gravity.CENTER_HORIZONTAL);
            }
        }

        // Header text/icon and any provider-created empty state should use the same palette too.
        for (View view : views) {
            boolean insideTile = false;
            for (ViewGroup tile : tiles) {
                if (isDescendantOf(view, tile)) { insideTile = true; break; }
            }
            if (insideTile) continue;
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                text.setTextColor(pal.onSurface);
                // Google's header is the authoritative structure name (Home, Cottage, …).
                // Make that name the obvious switch affordance as well as keeping the gear.
                int[] widgetAt = new int[2], textAt = new int[2];
                widget.getLocationOnScreen(widgetAt);
                text.getLocationOnScreen(textAt);
                if (!TextUtils.isEmpty(text.getText())
                        && textAt[1] - widgetAt[1] < svc.px(52)
                        && textAt[0] - widgetAt[0] < widget.getWidth() * .70f) {
                    HomeWidgetProfiles.label(svc, homeWidgetId, text.getText().toString());
                    if (homeLocationLabel != null)
                        setIfChanged(homeLocationLabel, text.getText().toString());
                    text.setClickable(true);
                    text.setContentDescription("Switch Google Home location");
                    text.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View ignored) { openHomeWidgetSetup(true); }
                    });
                }
            }
            else if (view instanceof ImageView)
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(pal.primary));
        }
    }

    /** Null means Home has not returned this control's live state yet. */
    private Boolean homeTileState(ViewGroup tile) {
        ArrayList<View> children = new ArrayList<View>();
        collectViews(tile, children);
        for (View child : children) if (child instanceof TextView) {
            String state = ((TextView) child).getText().toString().trim();
            if ("On".equalsIgnoreCase(state) || "Active".equalsIgnoreCase(state)) return true;
            if ("Off".equalsIgnoreCase(state) || "Inactive".equalsIgnoreCase(state)) return false;
        }
        return null;
    }

    /** The provider's delayed state arrives as a soft reveal instead of a RemoteViews pop. */
    private void animateHomeStateArrival(final ViewGroup tile, final boolean active,
                                         final int radius) {
        tile.animate().cancel();
        clearHomeTileBackgroundLayers(tile);
        tile.setBackground(ripple(round(pal.surfaceHigh, radius)));
        tile.setClipToOutline(true);
        tile.setOutlineProvider(roundOutline(radius));
        tintHomeControl(tile, false);

        final ArrayList<TextView> stateLabels = new ArrayList<TextView>();
        ArrayList<View> children = new ArrayList<View>();
        collectViews(tile, children);
        for (View child : children) if (child instanceof TextView) {
            TextView label = (TextView) child;
            String state = label.getText().toString().trim();
            if ("On".equalsIgnoreCase(state) || "Off".equalsIgnoreCase(state)
                    || "Active".equalsIgnoreCase(state) || "Inactive".equalsIgnoreCase(state)) {
                label.animate().cancel();
                label.setAlpha(0f);
                label.setTranslationY(svc.px(3));
                stateLabels.add(label);
            }
        }

        // RemoteViews can deliver the resolved state during its own attach/layout pass. The
        // old fallback applied the accent immediately in that case. Keep the neutral frame
        // visible once, then begin on the following display frame whether the tile was already
        // attached or only becomes attached after this callback.
        final Runnable start = new Runnable() { public void run() {
            startHomeStateArrival(tile, active, radius, stateLabels);
        }};
        if (tile.isAttachedToWindow()) {
            tile.postOnAnimation(start);
        } else {
            tile.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                public void onViewAttachedToWindow(View view) {
                    view.removeOnAttachStateChangeListener(this);
                    view.postOnAnimation(start);
                }
                public void onViewDetachedFromWindow(View view) {}
            });
        }
    }

    private void startHomeStateArrival(final ViewGroup tile, final boolean active,
                                       final int radius, final ArrayList<TextView> stateLabels) {
        if (!tile.isAttachedToWindow()) return;
        final int end = active ? pal.primary : pal.surfaceHigh;
        final int startPrimary = pal.onSurface;
        final int startSecondary = pal.onSurfaceVariant;
        final int endPrimary = active ? pal.onPrimary : pal.onSurface;
        final int endSecondary = active ? Palette.alpha(pal.onPrimary, 0xCC)
                : pal.onSurfaceVariant;
        final android.animation.ArgbEvaluator colours = new android.animation.ArgbEvaluator();
        android.animation.ValueAnimator colour = android.animation.ValueAnimator.ofArgb(
                pal.surfaceHigh, end);
        colour.setStartDelay(70);
        colour.setDuration(480);
        colour.setInterpolator(OPEN_EASE);
        colour.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                tile.setBackground(round((Integer) animation.getAnimatedValue(), radius));
                float fraction = animation.getAnimatedFraction();
                tintHomeControl(tile,
                        (Integer) colours.evaluate(fraction, startPrimary, endPrimary),
                        (Integer) colours.evaluate(fraction, startSecondary, endSecondary));
            }
        });
        colour.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                tile.setBackground(ripple(round(end, radius)));
                tintHomeControl(tile, active);
            }
        });
        colour.start();

        for (TextView label : stateLabels) {
            label.animate().alpha(1f).translationY(0f).setStartDelay(110).setDuration(360)
                    .setInterpolator(OPEN_EASE).start();
        }
    }

    /** Reveal our active background by removing Google's nested, opaque state layer. */
    private void clearHomeTileBackgroundLayers(ViewGroup tile) {
        ArrayList<View> children = new ArrayList<View>();
        collectViews(tile, children);
        for (View child : children) {
            if (child == tile) continue;
            boolean fullTile = child.getWidth() >= tile.getWidth() * .65f
                    && child.getHeight() >= tile.getHeight() * .65f;
            if (!fullTile) continue;
            if (child instanceof ImageView)
                ((ImageView) child).setImageDrawable(null); // Home's opaque state artwork
            if (child.getBackground() != null)
                child.setBackground(null);
        }
    }

    private void tintHomeControl(ViewGroup tile, boolean active) {
        int primary = active ? pal.onPrimary : pal.onSurface;
        int secondary = active ? Palette.alpha(pal.onPrimary, 0xCC) : pal.onSurfaceVariant;
        tintHomeControl(tile, primary, secondary);
    }

    private void tintHomeControl(ViewGroup tile, int primary, int secondary) {
        ArrayList<View> children = new ArrayList<View>();
        collectViews(tile, children);
        int textIndex = 0;
        for (View child : children) {
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(textIndex++ == 0 ? primary : secondary);
            } else if (child instanceof ImageView) {
                // Some Home versions use a tile-sized ImageView as their state background.
                // Tinting that "icon" paints the whole control black. Only tint real icons.
                if (child.getWidth() < tile.getWidth() * .60f
                        && child.getHeight() < tile.getHeight() * .60f)
                    ((ImageView) child).setImageTintList(ColorStateList.valueOf(primary));
            }
        }
    }

    private ViewOutlineProvider roundOutline(final int radius) {
        return new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }

    private boolean isDescendantOf(View view, ViewGroup ancestor) {
        if (view == ancestor) return true;
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void collectViews(View view, List<View> out) {
        out.add(view);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collectViews(group.getChildAt(i), out);
    }

    private void hideTrailingHomeChevron(ViewGroup tile) {
        int[] tileLocation = new int[2];
        tile.getLocationOnScreen(tileLocation);
        ArrayList<View> children = new ArrayList<View>();
        collectViews(tile, children);
        for (View child : children) {
            if (!(child instanceof ImageView) || child.getVisibility() != View.VISIBLE) continue;
            int[] location = new int[2];
            child.getLocationOnScreen(location);
            float center = location[0] + child.getWidth() / 2f;
            if (center > tileLocation[0] + tile.getWidth() * 0.68f) child.setVisibility(View.GONE);
        }
    }

    private void configureHomeWidget() {
        int id = Prefs.n(svc, Prefs.HOME_WIDGET_ID);
        AppWidgetProviderInfo info = id > 0
                ? AppWidgetManager.getInstance(svc).getAppWidgetInfo(id) : null;
        if (info == null) createHomeWidget();
        openHomeWidgetSetup(false);
    }

    private void openHomeWidgetSetup(boolean switcher) {
        launch(new Intent(svc, HomeWidgetSetupActivity.class)
                .putExtra(HomeWidgetSetupActivity.EXTRA_SWITCHER, switcher));
    }

    private void openHomeControls() {
        Intent home = svc.getPackageManager().getLaunchIntentForPackage("com.google.android.apps.chromecast.app");
        if (home != null) { launch(home); return; }
        if (Shell.privileged()) {
            closeNow();
            Shell.run("cmd statusbar click-tile controls", null);
        } else svc.closeShadeAndOpenSystem();
    }

    private boolean autoBrightness() {
        return Settings.System.getInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    // perceptual curve: the slider's halfway point is far dimmer than half the backlight
    private static int fromSlider(int p) { return Math.max(1, Math.round((float) Math.pow(p / 1000f, 2.2) * 255)); }
    private static int toSlider(int v) { return Math.round((float) Math.pow(Math.max(0, Math.min(255, v)) / 255f, 1 / 2.2) * 1000); }

    private void setExpanded(final boolean on) {
        if (expanded == on && Math.abs(expansionProgress - (on ? 1f : 0f)) < 0.01f) return;
        prepareExpansionDrag();
        settleExpansionProgress(on);
        chevron.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    private void prepareExpansionDrag() {
        if (expansionAnimator != null) {
            expansionAnimator.cancel();
            expansionAnimator = null;
        }
        if (quickPanel instanceof ViewGroup) {
            ViewGroup panel = (ViewGroup) quickPanel;
            if (expansionSavedTransition == null)
                expansionSavedTransition = panel.getLayoutTransition();
            panel.setLayoutTransition(null);
        }
        // quickPanel changes height every frame. Leaving the LayoutTransition on its parent
        // makes Android start/re-target a second bounds animation on every one of those frames,
        // so the Home shelf and notification list visibly judder behind the user's finger.
        if (content != null) {
            if (contentExpansionSavedTransition == null)
                contentExpansionSavedTransition = content.getLayoutTransition();
            content.setLayoutTransition(null);
        }
        applyExpansionProgress(expansionProgress);
    }

    /** Direct manipulation: one tile set follows the user's finger between both geometries. */
    private void applyExpansionProgress(float progress) {
        expansionProgress = Math.max(0f, Math.min(1f, progress));
        if (tileStage == null) return;
        int width = tileStage.getWidth();
        // Seed the exact screen-derived width before the first layout pass so the tile set
        // already occupies its compact geometry on frame one. Waiting for a posted layout
        // callback left every tile stacked at (0,0) for a frame, perceived as a late pop-in.
        if (width <= 0) width = Math.max(1, svc.screenW() - svc.px(40));
        int rows = Math.max(1, (morphTiles.size() + 3) / 4);
        int openHeight = rows * svc.px(60) + Math.max(0, rows - 1) * svc.px(10);
        ViewGroup.LayoutParams stageLp = tileStage.getLayoutParams();
        int nextHeight = Math.round(svc.px(50) + (openHeight - svc.px(50)) * expansionProgress);
        if (stageLp.height != nextHeight) {
            stageLp.height = nextHeight;
            tileStage.setLayoutParams(stageLp);
        }
        int sharedCount = 0;
        for (MorphTile tile : morphTiles) if (tile.sharedIndex >= 0) sharedCount++;
        sharedCount = Math.max(1, sharedCount);
        for (MorphTile tile : morphTiles) tile.applyMorph(expansionProgress, width, sharedCount);
        chevron.setRotation(180f * expansionProgress);
    }

    private void settleExpansionProgress(final boolean open) {
        final float start = expansionProgress;
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(
                start, open ? 1f : 0f);
        expansionAnimator = a;
        a.setDuration((long) (150 + 220 * Math.abs((open ? 1f : 0f) - start)));
        a.setInterpolator(open ? OPEN_EASE : CLOSE_EASE);
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator v) {
                applyExpansionProgress((Float) v.getAnimatedValue());
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (expansionAnimator == animation) expansionAnimator = null;
                if (cancelled) return;
                expanded = open;
                expansionProgress = open ? 1f : 0f;
                applyExpansionProgress(expansionProgress);
                chevron.setRotation(open ? 180f : 0f);
                chevron.setContentDescription(open ? "Collapse quick settings" : "Expand quick settings");
                if (quickPanel instanceof ViewGroup) {
                    ((ViewGroup) quickPanel).setLayoutTransition(expansionSavedTransition == null
                            ? polishedTransition() : expansionSavedTransition);
                }
                expansionSavedTransition = null;
                if (content != null) {
                    content.setLayoutTransition(contentExpansionSavedTransition == null
                            ? polishedTransition() : contentExpansionSavedTransition);
                }
                contentExpansionSavedTransition = null;
                scroll.smoothScrollTo(0, 0);
            }
        });
        a.start();
    }

    private LayoutTransition polishedTransition() {
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        lt.setDuration(LayoutTransition.APPEARING, 240);
        lt.setDuration(LayoutTransition.DISAPPEARING, 150);
        lt.setDuration(LayoutTransition.CHANGE_APPEARING, 280);
        lt.setDuration(LayoutTransition.CHANGE_DISAPPEARING, 220);
        lt.setDuration(LayoutTransition.CHANGING, 260);
        lt.setInterpolator(LayoutTransition.APPEARING, OPEN_EASE);
        lt.setInterpolator(LayoutTransition.DISAPPEARING, CLOSE_EASE);
        lt.setInterpolator(LayoutTransition.CHANGE_APPEARING, EMPHASIZED_EASE);
        lt.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING, EMPHASIZED_EASE);
        lt.setInterpolator(LayoutTransition.CHANGING, EMPHASIZED_EASE);
        return lt;
    }

    /**
     * Stock-like state change: opacity and geometry move on the same emphasized curve.
     * Call immediately before changing visibility, text bounds, or child membership.
     */
    private void beginMotionTransition(ViewGroup scene, long duration) {
        if (scene == null || !scene.isLaidOut() || scene.getWindowToken() == null) return;
        android.transition.TransitionSet set = new android.transition.TransitionSet();
        set.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        set.addTransition(new android.transition.Fade(
                android.transition.Fade.IN | android.transition.Fade.OUT));
        set.addTransition(new android.transition.ChangeBounds());
        set.addTransition(new android.transition.ChangeTransform());
        set.setDuration(duration);
        set.setInterpolator(EMPHASIZED_EASE);
        android.transition.TransitionManager.beginDelayedTransition(scene, set);
    }

    /** Fade an in-place visual mutation instead of replacing an icon or colour in one frame. */
    private void crossfadeMutation(final View view, final Runnable mutation) {
        if (view == null) return;
        view.animate().cancel();
        if (!view.isLaidOut() || view.getWindowToken() == null) {
            mutation.run();
            view.setAlpha(1f);
            view.setScaleX(1f);
            view.setScaleY(1f);
            return;
        }
        view.animate().alpha(0.18f).scaleX(0.94f).scaleY(0.94f).setDuration(85)
                .setInterpolator(CLOSE_EASE).withEndAction(new Runnable() { public void run() {
                    mutation.run();
                    view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190)
                            .setInterpolator(OPEN_EASE).start();
                }}).start();
    }

    /** INVISIBLE keeps media geometry stable; opacity still eases rather than snapping. */
    private void fadeInvisible(final View view, boolean show) {
        if (view == null) return;
        view.animate().cancel();
        if (!view.isLaidOut() || view.getWindowToken() == null) {
            view.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            view.setAlpha(1f);
            return;
        }
        if (show) {
            if (view.getVisibility() != View.VISIBLE) {
                view.setAlpha(0f);
                view.setVisibility(View.VISIBLE);
            }
            view.animate().alpha(1f).setDuration(220).setInterpolator(OPEN_EASE).start();
        } else if (view.getVisibility() == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(140).setInterpolator(CLOSE_EASE)
                    .withEndAction(new Runnable() { public void run() {
                        view.setVisibility(View.INVISIBLE);
                        view.setAlpha(1f);
                    }}).start();
        }
    }

    private void prepareChromeIntro() {
        int stockTint = pal.dark ? 0xFFFFFFFF : 0xFF111111;
        for (ImageView icon : headerStatusIcons)
            icon.setImageTintList(ColorStateList.valueOf(stockTint));
        // These views travel with the sheet and must be present on its very first revealed
        // pixel, especially during direct pull-down. Hiding them here and animating them only
        // after settle made the real circles/pills visibly pop in after the panel.
        for (View v : new View[]{headerView, tileStage}) {
            if (v == null) continue;
            v.animate().cancel();
            v.setAlpha(1f);
            v.setTranslationY(0f);
            v.setScaleX(1f);
            v.setScaleY(1f);
        }
        applyAccessoryReveal(homeShelfView, 0f, svc.px(18));
        for (View media : mediaIntroViews) applyAccessoryReveal(media, 0f, svc.px(22));
    }

    private void applyAccessoryReveal(View view, float progress, float distance) {
        if (view == null) return;
        float p = Math.max(0f, Math.min(1f, progress));
        view.animate().cancel();
        view.setAlpha(p);
        view.setTranslationY((1f - p) * distance);
    }

    private void finishAccessoryIntro(final View view, long delay) {
        if (view == null || view.getParent() == null) return;
        view.animate().cancel();
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(330)
                .setInterpolator(OPEN_EASE).withEndAction(new Runnable() { public void run() {
                    view.setLayerType(View.LAYER_TYPE_NONE, null);
                }}).start();
    }

    /** The panel lands first; its chrome then settles in as one short, tactile sequence. */
    private void runChromeIntroIfNeeded() {
        if (!chromeIntroPending || !attached) return;
        chromeIntroPending = false;
        final int stockTint = pal.dark ? 0xFFFFFFFF : 0xFF111111;
        android.animation.ValueAnimator tint = android.animation.ValueAnimator.ofArgb(
                stockTint, pal.primary, pal.onSurfaceVariant);
        tint.setStartDelay(35);
        tint.setDuration(520);
        tint.setInterpolator(EMPHASIZED_EASE);
        tint.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                int color = (Integer) a.getAnimatedValue();
                for (ImageView icon : headerStatusIcons)
                    if (icon.getVisibility() == View.VISIBLE)
                        icon.setImageTintList(ColorStateList.valueOf(color));
            }
        });
        tint.start();
        finishAccessoryIntro(homeShelfView, 25);
        long mediaDelay = 65;
        for (View media : mediaIntroViews) {
            finishAccessoryIntro(media, mediaDelay);
            mediaDelay += 35;
        }
        sheet.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
    }

    // ------------------------------------------------------------ tiles

    private void buildTiles() {
        tileStage.removeAllViews();
        morphTiles.clear();
        tileIds.clear();
        int shared = 0;
        for (int i = 0; i < TILE_IDS.length; i++) {
            int place = Prefs.n(svc, placeKey(TILE_IDS[i]));
            if (place == PLACE_HIDDEN) continue;
            Tile t = tile(TILE_IDS[i]);
            if (t == null) continue;
            tileIds.put(t, TILE_IDS[i]);
            int sharedIndex = place == PLACE_BOTH && shared < ROW_MAX ? shared++ : -1;
            MorphTile view = new MorphTile(t, morphTiles.size(), sharedIndex);
            morphTiles.add(view);
            tileStage.addView(view, new FrameLayout.LayoutParams(svc.px(50), svc.px(50)));
        }
        applyExpansionProgress(expanded ? 1f : 0f);
        tileStage.post(new Runnable() { public void run() {
            if (attached) applyExpansionProgress(expanded ? 1f : 0f);
        }});
    }

    /** A single tile view whose bounds, icon and labels interpolate between circle and pill. */
    private final class MorphTile extends FrameLayout {
        final Tile tile;
        final int gridIndex, sharedIndex;
        final ImageView icon;
        final LinearLayout words;
        final TextView name, state;
        final Paint tilePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        android.animation.ValueAnimator lookAnimator;
        boolean lookInitialized;
        int shownTileColor, shownIconColor, shownNameColor, shownStateColor;
        float visualWidth, visualHeight;

        MorphTile(final Tile tile, int gridIndex, int sharedIndex) {
            super(svc);
            this.tile = tile;
            this.gridIndex = gridIndex;
            this.sharedIndex = sharedIndex;
            setContentDescription(tile.label());
            setClipChildren(true);
            setWillNotDraw(false);
            icon = new ImageView(svc);
            icon.setImageResource(tile.icon());
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                    svc.px(24), svc.px(24), Gravity.CENTER_VERTICAL);
            iconLp.leftMargin = svc.px(6);
            addView(icon, iconLp);
            words = new LinearLayout(svc);
            words.setOrientation(LinearLayout.VERTICAL);
            name = tv(tile.label(), 12, pal.onSurface);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            state = tv("", 10, pal.onSurfaceVariant);
            state.setSingleLine(true);
            state.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(name);
            words.addView(state);
            FrameLayout.LayoutParams wordsLp = new FrameLayout.LayoutParams(
                    svc.px(80), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
            wordsLp.leftMargin = svc.px(33);
            addView(words, wordsLp);
            setOnClickListener(new View.OnClickListener() { public void onClick(View x) {
                press(x); tile.tap(); refreshTiles();
            }});
            setOnLongClickListener(openSettings(tile));
            refreshers.add(new Runnable() { public void run() { refreshLook(); }});
        }

        void refreshLook() {
            boolean on = tile.active();
            final int tileColor = on ? pal.primary : pal.surfaceHigh;
            final int iconColor = on ? pal.onPrimary : pal.onSurface;
            final int nameColor = on ? pal.onPrimary : pal.onSurface;
            final int stateColor = on ? Palette.alpha(pal.onPrimary, 0xCC) : pal.onSurfaceVariant;
            final CharSequence stateText = tile.state();
            if (!TextUtils.equals(state.getText(), stateText)) {
                if (!lookInitialized) state.setText(stateText);
                else crossfadeMutation(state, new Runnable() { public void run() {
                    state.setText(stateText);
                }});
            }
            if (!lookInitialized) {
                shownTileColor = tileColor;
                shownIconColor = iconColor;
                shownNameColor = nameColor;
                shownStateColor = stateColor;
                applyLookColors(tileColor, iconColor, nameColor, stateColor);
                lookInitialized = true;
                return;
            }
            if (shownTileColor == tileColor && shownIconColor == iconColor
                    && shownNameColor == nameColor && shownStateColor == stateColor) return;
            if (lookAnimator != null) lookAnimator.cancel();
            final int fromTile = shownTileColor, fromIcon = shownIconColor;
            final int fromName = shownNameColor, fromState = shownStateColor;
            final android.animation.ArgbEvaluator colors = new android.animation.ArgbEvaluator();
            lookAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
            lookAnimator.setDuration(260);
            lookAnimator.setInterpolator(EMPHASIZED_EASE);
            lookAnimator.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                public void onAnimationUpdate(android.animation.ValueAnimator animation) {
                    float p = (Float) animation.getAnimatedValue();
                    applyLookColors(
                            (Integer) colors.evaluate(p, fromTile, tileColor),
                            (Integer) colors.evaluate(p, fromIcon, iconColor),
                            (Integer) colors.evaluate(p, fromName, nameColor),
                            (Integer) colors.evaluate(p, fromState, stateColor));
                }
            });
            lookAnimator.start();
            shownTileColor = tileColor;
            shownIconColor = iconColor;
            shownNameColor = nameColor;
            shownStateColor = stateColor;
        }

        private void applyLookColors(int tileColor, int iconColor, int nameColor, int stateColor) {
            tilePaint.setColor(tileColor);
            icon.setImageTintList(ColorStateList.valueOf(iconColor));
            name.setTextColor(nameColor);
            state.setTextColor(stateColor);
            invalidate();
        }

        @Override protected void onDetachedFromWindow() {
            if (lookAnimator != null) lookAnimator.cancel();
            super.onDetachedFromWindow();
        }

        void applyMorph(float p, int stageWidth, int rowCount) {
            final int gap = svc.px(6), rowGap = svc.px(10), closedSize = svc.px(50);
            final int expandedWidth = Math.max(svc.px(64), (stageWidth - gap * 3) / 4);
            final int expandedHeight = svc.px(60);
            int ex = (gridIndex % 4) * (expandedWidth + gap);
            int ey = (gridIndex / 4) * (expandedHeight + rowGap);
            int origin = sharedIndex >= 0 ? sharedIndex : gridIndex % rowCount;
            float cell = stageWidth / (float) rowCount;
            int cx = Math.round(origin * cell + (cell - closedSize) / 2f);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            if (lp.width != expandedWidth || lp.height != expandedHeight
                    || lp.leftMargin != ex || lp.topMargin != ey) {
                lp.width = expandedWidth;
                lp.height = expandedHeight;
                lp.leftMargin = ex;
                lp.topMargin = ey;
                setLayoutParams(lp);          // once per width/configuration, never per frame
            }
            visualWidth = closedSize + (expandedWidth - closedSize) * p;
            visualHeight = closedSize + (expandedHeight - closedSize) * p;
            setTranslationX((cx - ex) * (1f - p));
            setTranslationY(-ey * (1f - p));
            float appear = sharedIndex >= 0 ? 1f : Math.max(0f, Math.min(1f, (p - 0.08f) / 0.72f));
            setAlpha(appear);
            int wantedVisibility = sharedIndex < 0 && p <= 0.001f ? View.INVISIBLE : View.VISIBLE;
            if (getVisibility() != wantedVisibility) setVisibility(wantedVisibility);
            boolean wantedClickable = sharedIndex >= 0 || p > 0.55f;
            if (isClickable() != wantedClickable) setClickable(wantedClickable);
            float iconScale = 1f - 0.25f * p;
            icon.setScaleX(iconScale);
            icon.setScaleY(iconScale);
            icon.setTranslationX((visualWidth / 2f - svc.px(18)) * (1f - p));
            FrameLayout.LayoutParams wp = (FrameLayout.LayoutParams) words.getLayoutParams();
            int wordWidth = Math.max(1, expandedWidth - wp.leftMargin - svc.px(5));
            if (wp.width != wordWidth) { wp.width = wordWidth; words.setLayoutParams(wp); }
            words.setAlpha(Math.max(0f, Math.min(1f, (p - 0.28f) / 0.58f)));
            invalidate();
        }

        @Override protected void dispatchDraw(Canvas canvas) {
            int save = canvas.save();
            canvas.clipRect(0f, 0f, visualWidth, visualHeight);
            canvas.drawRoundRect(0f, 0f, visualWidth, visualHeight,
                    visualHeight / 2f, visualHeight / 2f, tilePaint);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    && (event.getX() < 0 || event.getX() > visualWidth
                    || event.getY() < 0 || event.getY() > visualHeight)) return false;
            return super.onTouchEvent(event);
        }
    }

    private final HashMap<Tile, String> tileIds = new HashMap<Tile, String>();

    /** Long-press a tile: its full settings page. */
    private View.OnLongClickListener openSettings(final Tile t) {
        return new View.OnLongClickListener() { public boolean onLongClick(View v) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            String id = tileIds.get(t);
            String a;
            switch (id == null ? "" : id) {
                case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
                case "data": a = Settings.ACTION_DATA_ROAMING_SETTINGS; break;
                case "bluetooth": a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
                case "hotspot": a = "android.settings.TETHER_SETTINGS"; break;
                case "dnd": a = "android.settings.ZEN_MODE_SETTINGS"; break;
                case "sound": a = Settings.ACTION_SOUND_SETTINGS; break;
                case "rotate": case "reading": a = Settings.ACTION_DISPLAY_SETTINGS; break;
                case "location": a = Settings.ACTION_LOCATION_SOURCE_SETTINGS; break;
                case "nfc": a = Settings.ACTION_NFC_SETTINGS; break;
                case "airplane": a = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
                case "battery": a = Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
                case "ultra": case "reverse": openBattery(); return true;
                case "recorder": {
                    Intent i = recorderIntent();
                    if (i != null) launch(i);
                    return true;
                }
                default: return false;
            }
            if (!launchFirst(new Intent(a))) launch(new Intent(Settings.ACTION_SETTINGS));
            return true;
        }};
    }

    private void press(View v) {
        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction(new Runnable() {
            public void run() { /* spring back */ }
        }).start();
        v.postDelayed(new Runnable() { public void run() {
            v.animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
        }}, 70);
    }

    private Tile tile(String id) {
        final AudioManager audio = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
        final NotificationManager nm = (NotificationManager) svc.getSystemService(Context.NOTIFICATION_SERVICE);
        switch (id) {
            case "wifi": return new Tile() {
                public int icon() { return R.drawable.qs_wifi; }
                public String label() { return "Internet"; }
                public boolean active() {
                    try { return ((WifiManager) svc.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).isWifiEnabled(); }
                    catch (Exception e) { return false; }
                }
                public String state() { return active() ? "Wi-Fi on" : "Wi-Fi off"; }
                public void tap() {
                    if (!shell(active() ? "cmd wifi set-wifi-enabled disabled || svc wifi disable"
                            : "cmd wifi set-wifi-enabled enabled || svc wifi enable"))
                        launch(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                }
            };
            case "data": return new Tile() {
                public int icon() { return R.drawable.qs_data; }
                public String label() { return "Mobile data"; }
                public boolean active() {
                    return Settings.Global.getInt(svc.getContentResolver(), "mobile_data", 1) == 1;
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (!shell(active() ? "svc data disable" : "svc data enable"))
                        launch(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY));
                }
            };
            case "bluetooth": return new Tile() {
                public int icon() { return R.drawable.qs_bluetooth; }
                public String label() { return "Bluetooth"; }
                public boolean active() {
                    try {
                        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager)
                                svc.getSystemService(Context.BLUETOOTH_SERVICE);
                        return bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
                    } catch (Exception e) {
                        // Android 12+ guards BluetoothAdapter behind the Nearby devices
                        // runtime permission.  HyperOS still exposes this read-only state.
                        return Settings.Global.getInt(svc.getContentResolver(), "bluetooth_on", 0) == 1;
                    }
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (!shell(active() ? "cmd bluetooth_manager disable || svc bluetooth disable"
                            : "cmd bluetooth_manager enable || svc bluetooth enable"))
                        launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                }
            };
            case "flashlight":
                if (torchId == null) return null;
                return new Tile() {
                    public int icon() { return R.drawable.qs_flashlight; }
                    public String label() { return "Flashlight"; }
                    public boolean active() { return torchOn; }
                    public String state() { return torchOn ? "On" : "Off"; }
                    public void tap() {
                        final boolean wanted = !torchOn;
                        if (RootShell.available()) {
                            Shell.runResult(rootTool("torch " + torchId + " "
                                            + (wanted ? "on" : "off")),
                                    new Shell.ResultCallback() { public void done(Shell.Result result) {
                                        if (result.code != 0) svc.toast("Flashlight failed (" + result.code + ")");
                                        else torchOn = wanted;
                                        refreshTiles();
                                    }});
                            return;
                        }
                        try { cams.setTorchMode(torchId, wanted); torchOn = wanted; }
                        catch (Exception e) { svc.toast("Root is needed to control the flashlight"); }
                    }
                };
            case "dnd": return new Tile() {
                public int icon() { return R.drawable.qs_dnd; }
                public String label() { return "Do Not Disturb"; }
                public boolean active() {
                    return nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (RootShell.available()) {
                        shell("cmd notification set_dnd " + (active() ? "off" : "priority"));
                        return;
                    }
                    if (nm == null || !nm.isNotificationPolicyAccessGranted()) {
                        launch(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                        return;
                    }
                    nm.setInterruptionFilter(active() ? NotificationManager.INTERRUPTION_FILTER_ALL
                            : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                }
            };
            case "sound": return new Tile() {
                public int icon() {
                    return audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL ? R.drawable.qs_bell : R.drawable.qs_vibrate;
                }
                public String label() { return "Sound"; }
                public boolean active() { return audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL; }
                public String state() {
                    int m = audio.getRingerMode();
                    return m == AudioManager.RINGER_MODE_NORMAL ? "Ring" : m == AudioManager.RINGER_MODE_VIBRATE ? "Vibrate" : "Silent";
                }
                public void tap() {
                    try {
                        audio.setRingerMode(audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                                ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL);
                    } catch (Exception ignored) {}
                    buildTilesLater();   // its icon changes with the mode
                }
            };
            case "rotate": return new Tile() {
                public int icon() { return R.drawable.qs_rotate; }
                public String label() { return "Auto-rotate"; }
                public boolean active() {
                    return Settings.System.getInt(svc.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (!Settings.System.canWrite(svc)) { askWriteSettings(); return; }
                    try {
                        Settings.System.putInt(svc.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, active() ? 0 : 1);
                    } catch (Exception ignored) {}
                }
            };
            case "location": return new Tile() {
                public int icon() { return R.drawable.qs_location; }
                public String label() { return "Location"; }
                public boolean active() {
                    try { return ((LocationManager) svc.getSystemService(Context.LOCATION_SERVICE)).isLocationEnabled(); }
                    catch (Exception e) { return false; }
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (shell("cmd location set-location-enabled " + (!active())
                            + " || settings put secure location_mode "
                            + (active() ? Settings.Secure.LOCATION_MODE_OFF
                            : Settings.Secure.LOCATION_MODE_HIGH_ACCURACY))) return;
                    // with WRITE_SECURE_SETTINGS (granted once over adb) location really toggles
                    if (svc.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                            == PackageManager.PERMISSION_GRANTED) {
                        try {
                            Settings.Secure.putInt(svc.getContentResolver(), Settings.Secure.LOCATION_MODE,
                                    active() ? Settings.Secure.LOCATION_MODE_OFF : Settings.Secure.LOCATION_MODE_HIGH_ACCURACY);
                            return;
                        } catch (Exception ignored) {}
                    }
                    launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                }
            };
            case "airplane": return new Tile() {
                public int icon() { return R.drawable.qs_airplane; }
                public String label() { return "Airplane mode"; }
                public boolean active() {
                    return Settings.Global.getInt(svc.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (!shell("cmd connectivity airplane-mode " + (active() ? "disable" : "enable")))
                        launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS));
                }
            };
            case "hotspot": return new Tile() {
                public int icon() { return R.drawable.qs_hotspot; }
                public String label() { return "Hotspot"; }
                public boolean active() {
                    if (hotspot >= 0) return hotspot == 1;
                    try {   // no public getter; this hidden one is readable on most builds
                        Object wm = svc.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        return (Boolean) wm.getClass().getMethod("isWifiApEnabled").invoke(wm);
                    } catch (Throwable t) { return false; }
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (RootShell.available()) {
                        final int wanted = active() ? 0 : 1;
                        hotspot = wanted;
                        refreshTiles();
                        Shell.runResult(rootTool("hotspot " + (wanted == 1 ? "on" : "off")),
                                new Shell.ResultCallback() { public void done(Shell.Result result) {
                                    if (result.code != 0)
                                        svc.toast("Hotspot failed (" + result.code + ")");
                                    refreshHotspot();
                                    root.postDelayed(new Runnable() { public void run() {
                                        refreshHotspot();
                                    }}, 1200);
                                    root.postDelayed(new Runnable() { public void run() {
                                        refreshHotspot();
                                    }}, 3000);
                                }});
                        return;
                    }
                    if (!launchFirst(new Intent("android.settings.TETHER_SETTINGS"),
                            new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")))
                        launch(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                }
            };
            case "recorder": return new Tile() {
                public int icon() { return R.drawable.qs_recorder; }
                public String label() { return "Screen recorder"; }
                public boolean active() { return false; }
                public String state() { return "Open recorder"; }
                public void tap() {
                    if (RootShell.available()) {
                        closeNow();
                        RootFeatures.openRecorder(svc, new RootFeatures.ResultCallback() {
                            public void done(final boolean ok, final String detail) {
                                if (!ok) root.post(new Runnable() { public void run() {
                                    svc.toast(detail.isEmpty() ? "Screen recorder failed" : detail);
                                }});
                            }
                        });
                        return;
                    }
                    Intent i = recorderIntent();
                    if (i != null) launch(i);
                    else svc.toast("Root is needed to open the floating recorder");
                }
            };
            case "nfc": return new Tile() {
                public int icon() { return R.drawable.qs_nfc; }
                public String label() { return "NFC"; }
                public boolean active() {
                    try {
                        android.nfc.NfcAdapter a = android.nfc.NfcAdapter.getDefaultAdapter(svc);
                        return a != null && a.isEnabled();
                    } catch (Exception e) { return false; }
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (shell(active() ? "svc nfc disable" : "svc nfc enable")) return;
                    if (Build.VERSION.SDK_INT >= 29) launch(new Intent(Settings.Panel.ACTION_NFC));
                    else launch(new Intent(Settings.ACTION_NFC_SETTINGS));
                }
            };
            case "reading": return new Tile() {
                // HyperOS's reading mode; stock Android's nearest is Night Light
                public int icon() { return R.drawable.qs_reading; }
                public String label() { return "Reading mode"; }
                public boolean active() {
                    return Settings.System.getInt(svc.getContentResolver(), "screen_paper_mode_enabled", 0) == 1
                            || Settings.Secure.getInt(svc.getContentResolver(), "night_display_activated", 0) == 1;
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    boolean on = !active();
                    try { Settings.System.putInt(svc.getContentResolver(), "screen_paper_mode_enabled", on ? 1 : 0); return; }
                    catch (Throwable ignored) {}
                    try { Settings.Secure.putInt(svc.getContentResolver(), "night_display_activated", on ? 1 : 0); return; }
                    catch (Throwable ignored) {}
                    launch(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
                }
            };
            case "ultra": return new Tile() {
                public int icon() { return R.drawable.qs_ultra; }
                public String label() { return "Ultra battery saver"; }
                public boolean active() {
                    return Settings.System.getInt(svc.getContentResolver(),
                            "power_supersave_mode_open", 0) == 1;
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (!RootShell.available()) { openBattery(); return; }
                    boolean enable = !active();
                    shell("content call --uri content://com.miui.powercenter.powersaver"
                            + " --method changeSuperPowerMode"
                            + " --extra POWER_SUPERSAVE_MODE_OPEN:b:" + enable
                            + " --extra POWER_SUPERSAVE_MODE_FROMUSER:b:true");
                }
            };
            case "reverse": return new Tile() {
                public int icon() { return R.drawable.qs_reverse; }
                public String label() { return "Reverse charging"; }
                public boolean active() { return reverseCharging == 1; }
                public String state() {
                    return reverseCharging < 0 ? "Checking…" : active() ? "On" : "Off";
                }
                public void tap() {
                    if (!RootShell.available()) { openBattery(); return; }
                    final int wanted = active() ? 0 : 1;
                    reverseCharging = wanted;
                    refreshTiles();
                    Shell.runResult("printf " + wanted
                            + " > /sys/class/qcom-battery/reverse_chg_mode",
                            new Shell.ResultCallback() { public void done(Shell.Result result) {
                                if (result.code != 0)
                                    svc.toast("Reverse charging failed (" + result.code + ")");
                                refreshReverseCharging();
                            }});
                }
            };
            case "battery": return new Tile() {
                public int icon() { return R.drawable.qs_battery; }
                public String label() { return "Battery Saver"; }
                public boolean active() {
                    PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
                    return pm != null && pm.isPowerSaveMode();
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (!shell("cmd power set-mode " + (active() ? "0" : "1")))
                        launch(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
                }
            };
            default: return null;
        }
    }

    private void buildTilesLater() {
        root.post(new Runnable() { public void run() { if (attached) refreshAll(); }});
    }

    private void startTorchWatch() {
        try {
            cams = (CameraManager) svc.getSystemService(Context.CAMERA_SERVICE);
            if (torchId == null) {
                for (String id : cams.getCameraIdList()) {
                    Boolean f = cams.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    if (f != null && f) { torchId = id; break; }
                }
            }
            cams.registerTorchCallback(torchCb, null);
        } catch (Exception ignored) {}
    }

    private void stopTorchWatch() {
        try { if (cams != null) cams.unregisterTorchCallback(torchCb); } catch (Exception ignored) {}
    }

    private void refreshTiles() {
        if (!attached) return;
        List<Runnable> all = new ArrayList<Runnable>(fixed);
        all.addAll(refreshers);
        for (Runnable r : all) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    private void refreshAll() {
        refreshers.clear();
        buildTiles();
        refreshTiles();
        refreshNotifications();
    }

    // ------------------------------------------------------------ notifications

    /** A notification, or a group of them from one app / conversation, as one list entry. */
    private static final class Entry {
        final String group;
        final List<StatusBarNotification> kids = new ArrayList<StatusBarNotification>();
        StatusBarNotification summary;
        int section;   // 0 conversations, 1 notifications, 2 silent
        Entry(String g) { group = g; }
    }

    private final HashMap<String, Boolean> groupOpenState = new HashMap<String, Boolean>();
    /** Half-typed replies, by notification, kept while TickerBar runs. */
    private static final HashMap<String, String> drafts = new HashMap<String, String>();
    private Runnable replyClose;   // closes the reply box being typed in
    private final HashMap<String, Boolean> cardOpenState = new HashMap<String, Boolean>();
    private boolean swipingCard, refreshPending, replying;
    private NotificationListenerService.RankingMap rankMap;

    /** Called when notifications change while the shade is open. */
    void refreshNotifications() {
        if (!attached || notifBox == null) return;
        // never rebuild under a finger, or while a reply is being typed
        if (swipingCard || replying) { refreshPending = true; return; }
        refreshPending = false;
        boolean animateRefresh = notifBox.getChildCount() > 0 && !notificationIntroPending
                && notifBox.isLaidOut();
        if (animateRefresh) beginMotionTransition(notifBox, 300);
        notifBox.removeAllViews();
        clearable.clear();
        expanders.clear();
        collapsers.clear();
        entryViews.clear();
        mediaIntroViews.clear();
        rankMap = TickerListener.ranking();
        StatusBarNotification[] all = svc.lockNotificationsAllowed()
                ? TickerListener.active() : new StatusBarNotification[0];

        // one player per media app, the one playing first; a swiped-away one stays gone until it plays again
        // Media metadata is notification content too. Respect the global lock-screen switch;
        // individual private/public redaction cannot be mapped reliably from a media session,
        // so the global setting is the safe boundary here.
        List<MediaController> sessions = svc.lockNotificationsAllowed()
                ? playerSessions() : new ArrayList<MediaController>();
        HashSet<String> mediaPkgs = new HashSet<String>();
        HashSet<Object> live = new HashSet<Object>();
        for (MediaController mc : sessions) {
            mediaPkgs.add(mc.getPackageName());
            live.add(mc.getSessionToken());
            View m = mediaCard(mc);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = svc.px(10);
            notifBox.addView(m, lp);
            mediaIntroViews.add(m);
        }
        java.util.Iterator<java.util.Map.Entry<Object, Player>> pi = players.entrySet().iterator();
        while (pi.hasNext()) {
            java.util.Map.Entry<Object, Player> pe = pi.next();
            if (!live.contains(pe.getKey())) { pe.getValue().release(); pi.remove(); }
        }
        final MediaController session = sessions.isEmpty() ? null : sessions.get(0);

        List<Entry> list = entries(all, mediaPkgs);
        List<List<View>> rendered = new ArrayList<List<View>>();
        for (int i = 0; i < 3; i++) rendered.add(new ArrayList<View>());
        int renderedCount = 0;
        for (Entry en : list) {
            View c = en.kids.size() >= 2 ? groupCard(en) : card(en.kids.get(0), false);
            if (c == null) continue;
            rendered.get(en.section).add(c);
            renderedCount++;
        }

        if (renderedCount == 0) {
            if (session == null) {
                TextView none = tv(all == null ? "Notification access is off" : "No notifications", 14, pal.onSurfaceVariant);
                none.setGravity(Gravity.CENTER);
                none.setPadding(0, svc.px(28), 0, svc.px(28));
                notifBox.addView(none);
            }
        } else {
            int nonEmptySections = 0;
            for (List<View> section : rendered) if (!section.isEmpty()) nonEmptySections++;
            boolean several = nonEmptySections > 1;
            String[] heads = {"Messages", "Other notifications", "Quiet notifications"};
            for (int sec = 0; sec < 3; sec++) {
                if (rendered.get(sec).isEmpty()) continue;
                if (several) notifBox.addView(sectionHeader(heads[sec], sec == 2 ? list : null));
                for (View c : rendered.get(sec)) {
                    entryViews.add(c);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = svc.px(3);
                    notifBox.addView(c, lp);
                }
            }
        }

        if (!clearable.isEmpty()) {
            LinearLayout foot = new LinearLayout(svc);
            foot.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            foot.setPadding(svc.px(4), svc.px(10), svc.px(4), 0);
            foot.addView(pill("Clear all", new View.OnClickListener() { public void onClick(View v) { clearAll(); }}));
            notifBox.addView(foot);
        }
        runNotificationIntroIfNeeded();
    }

    private final List<View> clearable = new ArrayList<View>();   // swipeable entries, in list order
    private final HashMap<View, Runnable> expanders = new HashMap<View, Runnable>();   // entry -> expand it
    private final HashMap<View, Runnable> collapsers = new HashMap<View, Runnable>();  // entry -> fold it
    private final List<View> entryViews = new ArrayList<View>();                       // every card / group

    /** The notification entry (card or group) under a screen point, if any. */
    private View entryAt(float x, float y) {
        if (notifBox == null) return null;
        android.graphics.Rect r = new android.graphics.Rect();
        for (int i = 0; i < notifBox.getChildCount(); i++) {
            View c = notifBox.getChildAt(i);
            if (!entryViews.contains(c)) continue;
            if (c.getGlobalVisibleRect(r) && r.contains((int) x, (int) y)) return c;
        }
        return null;
    }

    /**
     * Stock Clear all: the entries sweep off to the side one after another from the bottom,
     * then the shade rolls up.
     */
    private static final long SWEEP_MS = 220;
    private static final long SWEEP_STAGGER_MS = SWEEP_MS / 10;

    /**
     * The temporal inverse of Clear all: cards return from the same side, top-to-bottom,
     * with the same duration and stagger. Clear all accelerates away bottom-to-top;
     * opening decelerates home top-to-bottom, so the stack reads as one physical object.
     */
    private void runNotificationIntroIfNeeded() {
        if (!notificationIntroPending || entryViews.isEmpty() || root == null) return;
        notificationIntroPending = false;
        final List<View> views = new ArrayList<View>(entryViews);
        root.post(new Runnable() { public void run() {
            if (!attached) return;
            long delay = 0;
            for (final View v : views) {
                if (v.getParent() == null) continue;
                float distance = Math.max(v.getWidth(), root.getWidth()) * 1.1f;
                v.animate().cancel();
                v.setTranslationX(distance);
                v.setAlpha(0f);
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                v.animate().translationX(0f).alpha(1f)
                        .setStartDelay(delay).setDuration(SWEEP_MS)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                        .withEndAction(new Runnable() { public void run() {
                            v.setLayerType(View.LAYER_TYPE_NONE, null);
                        }}).start();
                delay += SWEEP_STAGGER_MS;
            }
        }});
    }

    private void clearAll() {
        swipingCard = true;   // hold list rebuilds while they animate out
        List<View> views = new ArrayList<View>(clearable);
        long delay = 0;
        for (int i = views.size() - 1; i >= 0; i--) {
            View v = views.get(i);
            if (v.getParent() == null) continue;
            v.animate().translationX(v.getWidth() * 1.1f).alpha(0f).setStartDelay(delay).setDuration(SWEEP_MS)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f)).start();
            delay += SWEEP_STAGGER_MS;   // paired with the top-to-bottom opening inverse
        }
        root.postDelayed(new Runnable() { public void run() {
            TickerListener.cancelAll();
            swipingCard = false;
            close();
        }}, delay + SWEEP_MS + 40);
    }

    /** "Silent" gets its own clear button, like stock. */
    private View sectionHeader(String title, final List<Entry> clearSilent) {
        LinearLayout h = new LinearLayout(svc);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(svc.px(12), svc.px(12), svc.px(4), svc.px(6));
        TextView t = tv(title, 14, pal.onSurface);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (clearSilent != null) {
            TextView x = tv("✕", 15, pal.onSurfaceVariant);
            x.setPadding(svc.px(12), svc.px(4), svc.px(12), svc.px(4));
            x.setBackground(ripple(null));
            x.setContentDescription("Clear silent notifications");
            x.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                for (Entry en : clearSilent) {
                    if (en.section != 2) continue;
                    for (StatusBarNotification k : en.kids) if (k.isClearable()) TickerListener.cancel(k.getKey());
                }
            }});
            h.addView(x);
        }
        return h;
    }

    private NotificationListenerService.Ranking rank(String key) {
        if (rankMap == null) return null;
        NotificationListenerService.Ranking r = new NotificationListenerService.Ranking();
        return rankMap.getRanking(key, r) ? r : null;
    }

    /**
     * Ranking order, gathered into groups the way stock Android does: everything sharing a
     * group key (an app's explicit group, or the system's auto-group) is one entry, led by
     * its highest-ranked member; a summary only stands alone when it has no children. The
     * media notification the player already shows is left out.
     */
    private List<Entry> entries(StatusBarNotification[] all, java.util.Set<String> mediaPkgs) {
        List<Entry> out = new ArrayList<Entry>();
        if (all == null) return out;
        HashMap<String, StatusBarNotification> byKey = new HashMap<String, StatusBarNotification>();
        for (StatusBarNotification s : all) byKey.put(s.getKey(), s);
        List<String> keys = new ArrayList<String>();
        if (rankMap != null) for (String k : rankMap.getOrderedKeys()) if (byKey.containsKey(k)) keys.add(k);
        for (StatusBarNotification s : all) if (!keys.contains(s.getKey())) keys.add(s.getKey());

        HashMap<String, Entry> groups = new HashMap<String, Entry>();
        for (String k : keys) {
            StatusBarNotification s = byKey.get(k);
            if (svc.getPackageName().equals(s.getPackageName()) && !s.isClearable()) continue;
            if (Prefs.hidden(svc, s.getPackageName())) continue;
            if (Prefs.typeHidden(svc, s.getPackageName(), s.getNotification().getChannelId())) continue;
            if (svc.locked()) {
                int visibility = s.getNotification().visibility;
                if (visibility == Notification.VISIBILITY_SECRET) continue;
                if (!svc.lockPrivateAllowed() && visibility != Notification.VISIBILITY_PUBLIC) continue;
            }
            // media notifications live only as players, like stock: a stopped session (YouTube
            // leaves its notification behind) would otherwise sit here as an ongoing card that
            // can't be swiped away
            if (isMedia(s)) continue;
            String g = s.getGroupKey() != null ? s.getGroupKey() : s.getKey();
            Entry en = groups.get(g);
            if (en == null) { en = new Entry(g); groups.put(g, en); out.add(en); }
            if ((s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) en.summary = s;
            else en.kids.add(s);
        }
        List<Entry> shown = new ArrayList<Entry>();
        for (Entry en : out) {
            if (en.kids.isEmpty() && en.summary != null) en.kids.add(en.summary);
            if (en.kids.isEmpty()) continue;
            NotificationListenerService.Ranking r = rank(en.kids.get(0).getKey());
            boolean convo = r != null && Build.VERSION.SDK_INT >= 30 && r.isConversation();
            boolean silent = r != null && r.getImportance() < NotificationManager.IMPORTANCE_DEFAULT;
            en.section = silent ? 2 : convo ? 0 : 1;
            shown.add(en);
        }
        return shown;
    }

    private static boolean isMedia(StatusBarNotification s) {
        Bundle ex = s.getNotification().extras;
        return ex != null && ex.containsKey(Notification.EXTRA_MEDIA_SESSION);
    }

    // ------------------------------------------------------------ media player

    /**
     * The player is built once per session and updated in place: rebuilding it on every
     * playback change is what made it flicker (and start out plain, before the art loaded).
     * Artwork fills the whole card under a dark gradient, as in stock Android.
     */
    private final class Player {
        final MediaController mc;
        final FrameLayout card;
        final ImageView art, play;
        final View shade;
        final TextView title, artist, app, pos, dur;
        final SeekBar seek;
        android.graphics.Bitmap shownArt;
        boolean playing, playStateInitialized, shownPlaying;

        Player(final MediaController mc) {
            this.mc = mc;
            card = new FrameLayout(svc);
            card.setClipToOutline(true);
            card.setBackground(round(pal.primaryContainer, svc.px(28)));
            card.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                PendingIntent pi = mc.getSessionActivity();
                if (pi != null) svc.sendPending(pi); else svc.startFromShade(launchIntent(mc.getPackageName()));
                close();
            }});

            art = new ImageView(svc);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            shade = new View(svc);   // keeps white text readable on any cover
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{0xD9000000, 0x8C000000, 0x59000000});
            shade.setBackground(g);
            card.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout col = new LinearLayout(svc);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(svc.px(16), svc.px(10), svc.px(16), svc.px(8));
            card.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            app = tv(appLabel(mc.getPackageName()).toString(), 12, 0xB3FFFFFF);
            app.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            col.addView(app);
            title = tv("", 17, 0xFFFFFFFF);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(0, svc.px(2), 0, 0);
            artist = tv("", 12, 0xCCFFFFFF);
            artist.setSingleLine(true);
            artist.setEllipsize(TextUtils.TruncateAt.END);

            LinearLayout meta = new LinearLayout(svc);
            meta.setOrientation(LinearLayout.VERTICAL);
            meta.addView(title);
            meta.addView(artist);

            LinearLayout ctl = new LinearLayout(svc);
            ctl.setGravity(Gravity.CENTER);
            ctl.addView(button(R.drawable.qs_prev, "Previous", false, new Runnable() { public void run() {
                mc.getTransportControls().skipToPrevious(); }}));
            play = button(R.drawable.qs_play, "Play", true, new Runnable() { public void run() {
                if (playing) mc.getTransportControls().pause(); else mc.getTransportControls().play();
                playing = !playing;   // answer the tap at once; the session confirms a moment later
                showPlaying();
            }});
            ctl.addView(play);
            ctl.addView(button(R.drawable.qs_next, "Next", false, new Runnable() { public void run() {
                mc.getTransportControls().skipToNext(); }}));

            LinearLayout middle = new LinearLayout(svc);
            middle.setGravity(Gravity.CENTER_VERTICAL);
            middle.setPadding(0, svc.px(3), 0, 0);
            middle.addView(meta, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            middle.addView(ctl);
            col.addView(middle);

            LinearLayout prog = new LinearLayout(svc);
            prog.setGravity(Gravity.CENTER_VERTICAL);
            prog.setPadding(0, svc.px(2), 0, 0);
            pos = tv("0:00", 11, 0xB3FFFFFF);
            dur = tv("", 11, 0xB3FFFFFF);
            seek = new SeekBar(svc);
            seek.setProgressTintList(ColorStateList.valueOf(0xFFFFFFFF));
            seek.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
            seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x55FFFFFF));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user) pos.setText(DateUtils.formatElapsedTime(p / 1000));
                }
                public void onStartTrackingTouch(SeekBar s) { s.getParent().requestDisallowInterceptTouchEvent(true); }
                public void onStopTrackingTouch(SeekBar s) { mc.getTransportControls().seekTo(s.getProgress()); }
            });
            prog.addView(pos);
            prog.addView(seek, new LinearLayout.LayoutParams(0, svc.px(28), 1));
            prog.addView(dur);
            col.addView(prog);

            mc.registerCallback(cb, root.getHandler());
            update();
        }

        private ImageView button(int icon, String desc, boolean main, final Runnable r) {
            ImageView b = new ImageView(svc);
            b.setImageResource(icon);
            b.setContentDescription(desc);
            int size = svc.px(main ? 44 : 38);
            int pad = svc.px(main ? 12 : 10);
            b.setPadding(pad, pad, pad, pad);
            b.setImageTintList(ColorStateList.valueOf(main ? 0xFF111111 : 0xFFFFFFFF));
            b.setBackground(ripple(main ? round(0xFFFFFFFF, size / 2) : null));
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { press(v); r.run(); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart(svc.px(4));
            lp.setMarginEnd(svc.px(4));
            b.setLayoutParams(lp);
            return b;
        }

        final MediaController.Callback cb = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(android.media.session.PlaybackState s) { update(); }
            @Override public void onMetadataChanged(android.media.MediaMetadata m) { update(); }
            @Override public void onSessionDestroyed() { refreshNotifications(); }
        };

        void showPlaying() {
            if (playStateInitialized && shownPlaying == playing) return;
            final boolean next = playing;
            Runnable mutation = new Runnable() { public void run() {
                play.setImageResource(next ? R.drawable.qs_pause : R.drawable.qs_play);
                play.setContentDescription(next ? "Pause" : "Play");
            }};
            if (!playStateInitialized) mutation.run(); else crossfadeMutation(play, mutation);
            playStateInitialized = true;
            shownPlaying = next;
        }

        /** Refresh in place: only what changed is touched, so nothing flashes. */
        void update() {
            android.media.MediaMetadata md = mc.getMetadata();
            android.media.session.PlaybackState st = mc.getPlaybackState();
            if (md != null) {
                CharSequence t = md.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
                if (TextUtils.isEmpty(t)) t = md.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                CharSequence a = md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                if (TextUtils.isEmpty(a)) a = md.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
                setIfChanged(title, TextUtils.isEmpty(t) ? appLabel(mc.getPackageName()) : t);
                setIfChanged(artist, a == null ? "" : a);
                android.graphics.Bitmap bm = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bm == null) bm = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
                if (bm == null) bm = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (bm != shownArt) {
                    shownArt = bm;
                    if (bm != null) {
                        art.setImageBitmap(bm);
                        art.setAlpha(0f);
                        art.animate().alpha(1f).setDuration(220).start();   // the cover fades in, no pop
                    } else {
                        art.setImageDrawable(new android.graphics.drawable.GradientDrawable(
                                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                                new int[]{pal.primary, pal.primaryContainer}));
                    }
                }
                long d = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
                fadeInvisible(seek, d > 0);
                if (d > 0) { seek.setMax((int) Math.min(Integer.MAX_VALUE, d)); dur.setText(DateUtils.formatElapsedTime(d / 1000)); }
                else dur.setText("");
            }
            playing = st != null && (st.getState() == android.media.session.PlaybackState.STATE_PLAYING
                    || st.getState() == android.media.session.PlaybackState.STATE_BUFFERING);
            showPlaying();
            tick();
        }

        void tick() {
            android.media.session.PlaybackState st = mc.getPlaybackState();
            if (st != null && !seek.isPressed() && seek.getVisibility() == View.VISIBLE) {
                long p = position(st);
                seek.setProgress((int) Math.min(Integer.MAX_VALUE, p));
                pos.setText(DateUtils.formatElapsedTime(p / 1000));
            }
        }

        void release() { try { mc.unregisterCallback(cb); } catch (Exception ignored) {} }
    }

    private void setIfChanged(final TextView t, final CharSequence s) {
        if (TextUtils.equals(t.getText(), s)) return;
        crossfadeMutation(t, new Runnable() { public void run() { t.setText(s); }});
    }

    private final java.util.LinkedHashMap<Object, Player> players = new java.util.LinkedHashMap<Object, Player>();
    private final List<View> mediaIntroViews = new ArrayList<View>();
    /** Sessions swiped away; they come back when they start playing again. */
    private static final HashSet<Object> dismissedMedia = new HashSet<Object>();
    private final Runnable mediaTick = new Runnable() { public void run() {
        if (!attached || players.isEmpty()) return;
        for (Player p : players.values()) p.tick();
        root.postDelayed(this, 1000);
    }};

    private static long position(android.media.session.PlaybackState st) {
        long pos = st.getPosition();
        if (st.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
            pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime())
                    * st.getPlaybackSpeed());
        }
        return Math.max(0, pos);
    }

    /** Every session worth a player: playing ones first, then paused; one per app. */
    private List<MediaController> playerSessions() {
        List<MediaController> playing = new ArrayList<MediaController>(), paused = new ArrayList<MediaController>();
        HashSet<String> apps = new HashSet<String>();
        for (MediaController mc : svc.mediaSessions()) {
            if (Prefs.hidden(svc, mc.getPackageName()) || !apps.add(mc.getPackageName())) continue;
            android.media.session.PlaybackState st = mc.getPlaybackState();
            if (st == null || mc.getMetadata() == null) continue;
            int s = st.getState();
            boolean on = s == android.media.session.PlaybackState.STATE_PLAYING
                    || s == android.media.session.PlaybackState.STATE_BUFFERING;
            if (on) { dismissedMedia.remove(mc.getSessionToken()); playing.add(mc); }
            else if (s == android.media.session.PlaybackState.STATE_PAUSED && !dismissedMedia.contains(mc.getSessionToken()))
                paused.add(mc);
        }
        playing.addAll(paused);
        return playing;
    }

    /** The player for this session, kept across list refreshes; swipe it away to stop the app. */
    private View mediaCard(final MediaController mc) {
        Player p = players.get(mc.getSessionToken());
        if (p == null) {
            p = new Player(mc);
            players.put(mc.getSessionToken(), p);
            swipeToDismiss(p.card, new Runnable() { public void run() {
                dismissedMedia.add(mc.getSessionToken());
                try { mc.getTransportControls().pause(); mc.getTransportControls().stop(); } catch (Exception ignored) {}
                Player gone = players.remove(mc.getSessionToken());
                if (gone != null) gone.release();
            }});
            root.removeCallbacks(mediaTick);
            root.post(mediaTick);
        }
        ViewGroup parent = (ViewGroup) p.card.getParent();
        if (parent != null) parent.removeView(p.card);
        p.card.setTranslationX(0f);
        p.card.setAlpha(1f);
        return p.card;
    }

    // ------------------------------------------------------------ groups and cards

    /** A stock-style group: collapsed it previews the latest messages; expanded, each is its own. */
    /**
     * A stock-style group: collapsed it previews the latest messages; expanded, each is its own.
     * Both are built up front and swapped in place, so opening animates rather than rebuilding.
     */
    private View groupCard(final Entry en) {
        final StatusBarNotification lead = en.kids.get(0);
        Boolean savedGroupState = groupOpenState.get(en.group);
        final boolean[] open = {savedGroupState != null ? savedGroupState
                : Prefs.on(svc, Prefs.SHADE_NOTIFICATIONS_EXPANDED)};
        final LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(svc.px(16), svc.px(14), svc.px(8), svc.px(10));
        box.setBackground(ripple(round(Palette.alpha(pal.surface, panelA), svc.px(24))));
        box.setLayoutTransition(null);

        LinearLayout top = appLine(lead, en.kids.size());
        final LinearLayout preview = new LinearLayout(svc);
        preview.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout kids = new LinearLayout(svc);
        kids.setOrientation(LinearLayout.VERTICAL);
        final ImageView[] chev = new ImageView[1];
        final Runnable toggle = new Runnable() { public void run() {
            open[0] = !open[0];
            groupOpenState.put(en.group, open[0]);
            expanders.remove(box);
            collapsers.remove(box);
            if (open[0]) collapsers.put(box, this); else expanders.put(box, this);
            beginMotionTransition(box, 310);
            preview.setVisibility(open[0] ? View.GONE : View.VISIBLE);
            kids.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            chev[0].animate().rotation(open[0] ? 180f : 0f).setDuration(300)
                    .setInterpolator(EMPHASIZED_EASE).start();
            chev[0].setContentDescription(open[0] ? "Collapse" : "Expand");
        }};
        chev[0] = chevron(open[0], toggle);
        top.addView(chev[0], new LinearLayout.LayoutParams(svc.px(40), svc.px(40)));
        if (open[0]) collapsers.put(box, toggle); else expanders.put(box, toggle);
        box.addView(top);

        int n = Math.min(2, en.kids.size());
        for (int i = 0; i < n; i++) {
            Notification x = en.kids.get(i).getNotification();
            CharSequence t = x.extras == null ? null : x.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence b = x.extras == null ? null : x.extras.getCharSequence(Notification.EXTRA_TEXT);
            android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
            if (!TextUtils.isEmpty(t)) {
                line.append(t);
                line.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, line.length(), 0);
                line.append("  ");
            }
            if (!TextUtils.isEmpty(b)) line.append(b);
            TextView row = tv("", 14, notificationTextColor);
            row.setText(line);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(0, svc.px(i == 0 ? 8 : 2), svc.px(8), 0);
            preview.addView(row);
        }
        if (en.kids.size() > 2) {
            TextView more = tv("+" + (en.kids.size() - 2) + " more", 13, pal.onSurfaceVariant);
            more.setPadding(0, svc.px(4), 0, 0);
            preview.addView(more);
        }
        for (StatusBarNotification k : en.kids) {
            View c = card(k, true);
            if (c == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = svc.px(6);
            lp.setMarginEnd(svc.px(8));
            kids.addView(c, lp);
        }
        preview.setVisibility(open[0] ? View.GONE : View.VISIBLE);
        kids.setVisibility(open[0] ? View.VISIBLE : View.GONE);
        box.addView(preview);
        box.addView(kids);

        box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (open[0]) { toggle.run(); return; }   // an open group folds back up
            // tapping the closed group opens the app, as stock does
            StatusBarNotification s = en.summary != null && en.summary.getNotification().contentIntent != null
                    ? en.summary : lead;
            if (s.getNotification().contentIntent != null) svc.sendPending(s.getNotification().contentIntent);
            else svc.startFromShade(launchIntent(s.getPackageName()));
            for (StatusBarNotification k : en.kids)
                if ((k.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0) TickerListener.cancel(k.getKey());
            if (en.summary != null && (en.summary.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0)
                TickerListener.cancel(en.summary.getKey());
            close();
        }});
        final List<StatusBarNotification> all = new ArrayList<StatusBarNotification>(en.kids);
        if (en.summary != null && !all.contains(en.summary)) all.add(en.summary);
        View menu = menuRow(lead, all);
        box.addView(menu);
        box.setOnLongClickListener(toggleMenu(menu));
        boolean clearableGroup = false;
        for (StatusBarNotification k : en.kids) if (k.isClearable()) clearableGroup = true;
        if (clearableGroup) clearable.add(box);
        if (clearableGroup) swipeToDismiss(box, new Runnable() { public void run() {
            for (StatusBarNotification k : all) if (k.isClearable()) TickerListener.cancel(k.getKey());
        }});
        return box;
    }

    private ImageView chevron(boolean open, final Runnable toggle) {
        ImageView chev = new ImageView(svc);
        chev.setImageResource(R.drawable.qs_chevron);
        chev.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        chev.setRotation(open ? 180f : 0f);
        chev.setPadding(svc.px(8), svc.px(8), svc.px(8), svc.px(8));
        chev.setBackground(ripple(round(pal.surfaceHigh, svc.px(16))));
        chev.setContentDescription(open ? "Collapse" : "Expand");
        // Consume the gesture here. The notification card also owns a swipe listener and a
        // content click; relying on click bubbling made this tiny control unreliable.
        chev.setClickable(true);
        chev.setOnTouchListener(new View.OnTouchListener() { public boolean onTouch(View v, MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                v.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                v.setPressed(false);
                v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                toggle.run();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                return true;
            }
            return true;
        }});
        return chev;
    }

    private Intent launchIntent(String pkg) {
        Intent i = svc.getPackageManager().getLaunchIntentForPackage(pkg);
        return i != null ? i : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
    }

    /** Icon, app name, optional count, time: the line at the top of every card. */
    private LinearLayout appLine(StatusBarNotification s, int count) {
        Notification n = s.getNotification();
        LinearLayout top = new LinearLayout(svc);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView small = new ImageView(svc);
        try {
            Drawable d = n.getSmallIcon() == null ? null : n.getSmallIcon().loadDrawable(svc);
            if (d != null) small.setImageDrawable(d.mutate());
        } catch (Exception ignored) {}
        int accent = n.color != 0 && n.color != Notification.COLOR_DEFAULT ? n.color : pal.primary;
        small.setImageTintList(ColorStateList.valueOf(pal.onPrimary));
        small.setPadding(svc.px(4), svc.px(4), svc.px(4), svc.px(4));
        small.setBackground(round(accent, svc.px(12)));
        top.addView(small, new LinearLayout.LayoutParams(svc.px(24), svc.px(24)));
        TextView app = tv(appLabel(s.getPackageName()) + (count > 1 ? "  ·  " + count : "") + "  ·  "
                + DateUtils.getRelativeTimeSpanString(s.getPostTime(), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE), 12, pal.onSurfaceVariant);
        app.setPadding(svc.px(8), 0, 0, 0);
        app.setSingleLine(true);
        top.addView(app, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return top;
    }

    /** Long-press menu, stock style: snooze, the app's notification settings, or hide the app. */
    private View menuRow(final StatusBarNotification s, final List<StatusBarNotification> all) {
        final LinearLayout wrap = new LinearLayout(svc);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setVisibility(View.GONE);
        final LinearLayout m = new LinearLayout(svc);
        m.setGravity(Gravity.CENTER_VERTICAL);
        m.setPadding(0, svc.px(10), 0, 0);
        wrap.addView(m);
        final LinearLayout row2 = new LinearLayout(svc);
        row2.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row2.setPadding(0, svc.px(8), 0, 0);
        wrap.addView(row2);
        final String[] labels = {"15m", "1h", "2h"};
        final long[] times = {15 * 60000L, 60 * 60000L, 120 * 60000L};
        TextView z = tv("Snooze", 13, pal.onSurfaceVariant);
        z.setPadding(0, 0, svc.px(6), 0);
        m.addView(z);
        for (int i = 0; i < labels.length; i++) {
            final long ms = times[i];
            TextView b = pill(labels[i], new View.OnClickListener() { public void onClick(View v) {
                for (StatusBarNotification k : all) TickerListener.snooze(k.getKey(), ms);
                svc.toast("Snoozed");
            }});
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(svc.px(6));
            m.addView(b, lp);
        }
        m.addView(new View(svc), new LinearLayout.LayoutParams(0, 1, 1));
        TextView set = pill("⚙", new View.OnClickListener() { public void onClick(View v) {
            Intent i;
            String ch = s.getNotification().getChannelId();
            if (ch != null) i = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, s.getPackageName()).putExtra(Settings.EXTRA_CHANNEL_ID, ch);
            else i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, s.getPackageName());
            launch(i);
        }});
        set.setContentDescription("Notification settings");
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.setMarginEnd(svc.px(6));
        m.addView(set, sl);
        // hiding takes a second tap: the first only arms the button
        final String ch = s.getNotification().getChannelId();
        if (ch != null) {
            NotificationListenerService.Ranking rk = rank(s.getKey());
            final String chName = rk != null && rk.getChannel() != null && rk.getChannel().getName() != null
                    ? rk.getChannel().getName().toString() : ch;
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tl.setMarginEnd(svc.px(6));
            row2.addView(confirmPill("Hide this type", "Hide \u201c" + chName + "\u201d?", new Runnable() { public void run() {
                Prefs.setTypeHidden(svc, s.getPackageName(), ch, chName, true);
                svc.toast("Hidden: " + appLabel(s.getPackageName()) + " \u2014 " + chName);
                refreshNotifications();
            }}), tl);
        }
        row2.addView(confirmPill("Hide app", "Tap again to hide", new Runnable() { public void run() {
            Prefs.setHidden(svc, s.getPackageName(), true);
            svc.toast("Hidden: " + appLabel(s.getPackageName()) + " \u2014 undo in TickerBar \u2192 Hidden apps");
            refreshNotifications();
        }}));
        return wrap;
    }

    /** A destructive pill: the first tap arms it (red, for 3 s), the second does it. */
    private TextView confirmPill(final String label, final String armed, final Runnable action) {
        final TextView p = pill(label, null);
        p.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (p.getTag() == null) {
                p.setTag(Boolean.TRUE);
                crossfadeMutation(p, new Runnable() { public void run() {
                    p.setText(armed);
                    p.setTextColor(0xFFFFFFFF);
                    p.setBackground(ripple(round(pal.dark ? 0xFFB3261E : 0xFFD32F2F,
                            svc.px(18))));
                }});
                p.postDelayed(new Runnable() { public void run() {
                    p.setTag(null);
                    crossfadeMutation(p, new Runnable() { public void run() {
                        p.setText(label);
                        p.setTextColor(pal.onSurface);
                        p.setBackground(ripple(round(pal.surfaceHigh, svc.px(18))));
                    }});
                }}, 3000);
                return;
            }
            action.run();
        }});
        return p;
    }

    private View.OnLongClickListener toggleMenu(final View menu) {
        return new View.OnLongClickListener() { public boolean onLongClick(View v) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            ViewGroup scene = v instanceof ViewGroup ? (ViewGroup) v
                    : v.getParent() instanceof ViewGroup ? (ViewGroup) v.getParent() : null;
            beginMotionTransition(scene, 300);
            menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        }};
    }

    /** The action that takes a typed reply, if the app offers one. */
    private static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            if (a == null || a.actionIntent == null || a.getRemoteInputs() == null) continue;
            for (android.app.RemoteInput ri : a.getRemoteInputs()) if (ri.getAllowFreeFormInput()) return a;
        }
        return null;
    }

    /** Send a reply through the app's own reply action, as the system shade does. */
    private void reply(Notification.Action a, CharSequence text, boolean choice) {
        android.app.RemoteInput[] ris = a.getRemoteInputs();
        Intent fill = new Intent();
        Bundle res = new Bundle();
        for (android.app.RemoteInput ri : ris) res.putCharSequence(ri.getResultKey(), text);
        android.app.RemoteInput.addResultsToIntent(ris, fill, res);
        if (Build.VERSION.SDK_INT >= 28) android.app.RemoteInput.setResultsSource(fill,
                choice ? android.app.RemoteInput.SOURCE_CHOICE : android.app.RemoteInput.SOURCE_FREE_FORM_INPUT);
        boolean ok = svc.sendPending(a.actionIntent, fill);
        svc.toast(ok ? "Sent" : "Couldn't send");
    }

    /** One notification. Inside an open group it drops the app line and sits on the group's surface. */
    private View card(final StatusBarNotification s, boolean child) {
        final Notification n = s.getNotification();
        Bundle ex = n.extras;
        CharSequence title = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = text(n);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(body)) return null;
        Boolean savedCardState = cardOpenState.get(s.getKey());
        final boolean[] open = {savedCardState != null ? savedCardState
                : Prefs.on(svc, Prefs.SHADE_NOTIFICATIONS_EXPANDED)};

        final LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutTransition(null);
        LinearLayout top = null;
        if (child) {
            card.setPadding(svc.px(14), svc.px(10), svc.px(14), svc.px(10));
            card.setBackground(ripple(round(pal.surfaceHigh, svc.px(18))));
        } else {
            card.setPadding(svc.px(16), svc.px(14), svc.px(16), svc.px(16));
            card.setBackground(ripple(round(Palette.alpha(pal.surface, panelA), svc.px(24))));
            top = appLine(s, 1);
            card.addView(top);
        }

        // Title + text, with the sender's picture aligned to the first text line. Centring
        // the avatar against a multi-line body made conversation cards look accidental.
        LinearLayout mid = new LinearLayout(svc);
        mid.setGravity(Gravity.TOP);
        LinearLayout words = new LinearLayout(svc);
        words.setOrientation(LinearLayout.VERTICAL);
        if (!TextUtils.isEmpty(title)) {
            TextView t = tv(title.toString(), 16, pal.onSurface);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(0, 0, 0, svc.px(3));
            words.addView(t);
        }
        // MessagingStyle apps often repeat "Sender: " even though Sender is already the title.
        // Remove only that exact duplicate prefix; multi-person transcripts remain untouched.
        CharSequence shownBody = body;
        if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(body)) {
            String prefix = title.toString() + ": ";
            if (body.toString().startsWith(prefix)) shownBody = body.toString().substring(prefix.length());
        }
        final int shortLines = 1;
        final TextView bodyView = TextUtils.isEmpty(shownBody) ? null : tv(shownBody.toString(), 14, notificationTextColor);
        if (bodyView != null) {
            bodyView.setMaxLines(open[0] ? 30 : shortLines);
            bodyView.setEllipsize(TextUtils.TruncateAt.END);
            bodyView.setLineSpacing(svc.px(2), 1.08f);
            words.addView(bodyView);
        }
        mid.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Icon large = n.getLargeIcon();
        if (large != null) {
            try {
                Drawable d = large.loadDrawable(svc);
                if (d != null) {
                    ImageView li = new ImageView(svc);
                    li.setImageDrawable(d);
                    li.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    li.setClipToOutline(true);
                    li.setBackground(round(pal.surfaceHigh, svc.px(20)));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(svc.px(40), svc.px(40));
                    lp.setMarginStart(svc.px(12));
                    lp.topMargin = svc.px(1);
                    mid.addView(li, lp);
                }
            } catch (Exception ignored) {}
        }
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!child) mp.topMargin = svc.px(11);
        card.addView(mid, mp);

        // expand: the whole text, and the picture for picture notifications
        Drawable picture = picture(n);
        // The collapsed body is one line. Character counts are not a useful overflow test:
        // glyph widths, font scale, screen width and an optional avatar all change how much
        // fits. Measure the exact text we render in the actual space the words receive, so a
        // chevron is present only when opening the card can reveal another line (or picture).
        int collapsedBodyWidth = svc.screenW() - svc.px(8 * 2)    // content side insets
                - svc.px(16 * 2)                                 // card side padding
                - (large == null ? 0 : svc.px(40 + 12));          // avatar + leading gap
        boolean bodyOverflows = false;
        if (bodyView != null && collapsedBodyWidth > 0) {
            String rendered = shownBody.toString();
            bodyOverflows = rendered.contains("\n")
                    || android.text.Layout.getDesiredWidth(rendered, bodyView.getPaint())
                    > collapsedBodyWidth;
        }
        final boolean expandable = picture != null || bodyOverflows;
        final ImageView[] pics = new ImageView[1];
        if (expandable && top != null) {
            final ImageView[] chev = new ImageView[1];
            final Runnable toggle = new Runnable() { public void run() {
                open[0] = !open[0];
                cardOpenState.put(s.getKey(), open[0]);
                expanders.remove(card);
                collapsers.remove(card);
                if (open[0]) collapsers.put(card, this); else expanders.put(card, this);
                beginMotionTransition(card, 310);
                if (bodyView != null) bodyView.setMaxLines(open[0] ? 30 : shortLines);   // the card grows, animated
                if (pics[0] != null) {
                    pics[0].setVisibility(open[0] ? View.VISIBLE : View.GONE);
                }
                chev[0].animate().rotation(open[0] ? 180f : 0f).setDuration(300)
                        .setInterpolator(EMPHASIZED_EASE).start();
                chev[0].setContentDescription(open[0] ? "Collapse" : "Expand");
            }};
            chev[0] = chevron(open[0], toggle);
            top.addView(chev[0], new LinearLayout.LayoutParams(svc.px(40), svc.px(40)));
            if (open[0]) collapsers.put(card, toggle); else expanders.put(card, toggle);
        }
        if (picture != null) {
            ImageView pic = new ImageView(svc);
            pics[0] = pic;
            pic.setVisibility(open[0] ? View.VISIBLE : View.GONE);
            pic.setImageDrawable(picture);
            pic.setAdjustViewBounds(true);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setClipToOutline(true);
            pic.setBackground(round(pal.surfaceHigh, svc.px(16)));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            pl.topMargin = svc.px(10);
            card.addView(pic, pl);
        }

        // One action rail for both system-suggested replies and app actions. Splitting these
        // into separate LinearLayouts made cards grow into two action rows even when there
        // was ample horizontal room. Chips never wrap; the rail scrolls when it truly overflows.
        final LinearLayout actionChips = new LinearLayout(svc);
        actionChips.setOrientation(LinearLayout.HORIZONTAL);
        actionChips.setGravity(Gravity.CENTER_VERTICAL);
        actionChips.setPadding(0, svc.px(10), svc.px(4), 0);
        final int[] actionChipCount = {0};

        // smart replies the system suggested for this message
        final Notification.Action replyA = replyAction(n);
        NotificationListenerService.Ranking r = rank(s.getKey());
        if (replyA != null && r != null && Build.VERSION.SDK_INT >= 29) {
            List<CharSequence> smart = r.getSmartReplies();
            if (smart != null && !smart.isEmpty()) {
                int shown = 0;
                for (final CharSequence q : smart) {
                    if (shown++ >= 3) break;
                    TextView c = actionPill(q.toString(), new View.OnClickListener() { public void onClick(View v) {
                        reply(replyA, q, true);
                    }});
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMarginEnd(svc.px(8));
                    actionChips.addView(c, lp);
                    actionChipCount[0]++;
                }
            }
        }

        // the inline reply box, shown when Reply is tapped
        final LinearLayout replyBox = new LinearLayout(svc);
        replyBox.setGravity(Gravity.CENTER_VERTICAL);
        replyBox.setVisibility(View.GONE);
        replyBox.setPadding(svc.px(4), svc.px(8), 0, 0);
        final android.widget.EditText input = new android.widget.EditText(svc);
        input.setHint("Reply");
        input.setHintTextColor(pal.onSurfaceVariant);
        input.setTextColor(pal.onSurface);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setBackground(round(pal.surfaceHigh, svc.px(22)));
        input.setPadding(svc.px(16), svc.px(10), svc.px(16), svc.px(10));
        input.setMaxLines(4);

        replyBox.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView send = new ImageView(svc);
        send.setImageResource(R.drawable.qs_send);
        send.setImageTintList(ColorStateList.valueOf(pal.onPrimary));
        send.setPadding(svc.px(11), svc.px(11), svc.px(11), svc.px(11));
        send.setBackground(ripple(round(pal.primary, svc.px(22))));
        send.setContentDescription("Send");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(svc.px(44), svc.px(44));
        sendLp.setMarginStart(svc.px(8));
        replyBox.addView(send, sendLp);
        final Runnable closeReply = new Runnable() { public void run() {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    svc.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            beginMotionTransition(card, 280);
            replyBox.setVisibility(View.GONE);   // the draft stays for next time
            replying = false;
            replyClose = null;
            setReplyFocusable(false);
            if (refreshPending) refreshNotifications();
        }};
        input.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence q, int a, int b, int c) {}
            public void onTextChanged(CharSequence q, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable ed) {
                if (ed.length() == 0) drafts.remove(s.getKey()); else drafts.put(s.getKey(), ed.toString());
            }
        });
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() { public void onFocusChange(View v, boolean f) {
            if (f) { replying = true; replyClose = closeReply; }   // typing into a restored draft
        }});
        String draft = drafts.get(s.getKey());
        if (draft != null && replyA != null) {   // a reply you started earlier: back where you left it
            input.setText(draft);
            input.setSelection(draft.length());
            replyBox.setVisibility(View.VISIBLE);
        }

        // actions: replies open the inline box; the rest fire straight away
        if (n.actions != null && n.actions.length > 0) {
            int shown = 0;
            for (final Notification.Action a : n.actions) {
                if (a == null || TextUtils.isEmpty(a.title) || a.actionIntent == null || shown >= 3) continue;
                // Some apps publish an "Expand" action of their own. Do not render an
                // action chip when this card has no second visual state to reveal.
                if (!expandable && isExpansionAction(a.title)) continue;
                final boolean isReply = a == replyA;
                TextView b = actionPill(a.title.toString(), new View.OnClickListener() { public void onClick(View v) {
                    if (isReply) {
                        beginMotionTransition(card, 300);
                        replyBox.setVisibility(View.VISIBLE);
                        replying = true;
                        replyClose = closeReply;
                        setReplyFocusable(true);
                        input.requestFocus();
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                                svc.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(input, 0);
                        return;
                    }
                    svc.sendPending(a.actionIntent);
                    // an action that opens something closes the shade; one that works in the
                    // background (Like, Mark as read) stays put and the card sweeps away
                    if (Build.VERSION.SDK_INT >= 31 && !a.actionIntent.isActivity()) {
                        fly(card, svc.px(1), 2000, new Runnable() { public void run() {
                            if (s.isClearable()) TickerListener.cancel(s.getKey());
                        }});
                    } else {
                        close();
                    }
                }});
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(svc.px(8));
                actionChips.addView(b, lp);
                actionChipCount[0]++;
                shown++;
            }
        }
        if (actionChipCount[0] > 0) {
            HorizontalScrollView rail = new HorizontalScrollView(svc);
            rail.setHorizontalScrollBarEnabled(false);
            rail.setOverScrollMode(View.OVER_SCROLL_NEVER);
            rail.setFillViewport(false);
            rail.addView(actionChips, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(rail, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(replyBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        send.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            CharSequence msg = input.getText();
            if (replyA == null || TextUtils.isEmpty(msg)) return;
            reply(replyA, msg.toString(), false);
            input.setText("");
            drafts.remove(s.getKey());
            closeReply.run();
        }});

        List<StatusBarNotification> one = new ArrayList<StatusBarNotification>();
        one.add(s);
        View menu = menuRow(s, one);
        card.addView(menu);

        card.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (n.contentIntent != null) svc.sendPending(n.contentIntent);
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) TickerListener.cancel(s.getKey());
            close();
        }});
        card.setOnLongClickListener(toggleMenu(menu));
        if (s.isClearable() && !child) clearable.add(card);
        if (s.isClearable()) swipeToDismiss(card,
                new Runnable() { public void run() { TickerListener.cancel(s.getKey()); } }, child);
        return card;
    }

    private static boolean isExpansionAction(CharSequence title) {
        if (title == null) return false;
        String value = title.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return "expand".equals(value) || "collapse".equals(value)
                || "show more".equals(value) || "show less".equals(value)
                || "more".equals(value);
    }

    /** A picture notification's image, if it has one. */
    private Drawable picture(Notification n) {
        Bundle ex = n.extras;
        if (ex == null) return null;
        try {
            android.graphics.Bitmap bm = ex.getParcelable(Notification.EXTRA_PICTURE);
            if (bm != null) return new android.graphics.drawable.BitmapDrawable(svc.getResources(), bm);
            if (Build.VERSION.SDK_INT >= 31) {
                Icon ic = ex.getParcelable(Notification.EXTRA_PICTURE_ICON);
                if (ic != null) return ic.loadDrawable(svc);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Stock-style swipe: the card stays under the finger (back and forth, as long as you
     * hold it), fading as it travels. Any sideways movement means it's not a tap; a quick
     * flick of a couple of centimetres is enough to dismiss, and so is dragging past ~35%.
     */
    private void swipeToDismiss(final View card, final Runnable dismiss) {
        swipeToDismiss(card, dismiss, false);
    }

    /** Child cards capture DOWN so the enclosing app group cannot steal their swipe. */
    private void swipeToDismiss(final View card, final Runnable dismiss, final boolean captureDown) {
        card.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0; boolean swiping, moved, vertical; VelocityTracker vt;
            public boolean onTouch(View v, MotionEvent e) {
                int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
                if (vt != null && e.getActionMasked() != MotionEvent.ACTION_DOWN) track(vt, e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY();
                        swiping = false; moved = false; vertical = false;
                        v.animate().cancel();
                        if (vt != null) vt.recycle();
                        vt = VelocityTracker.obtain(); track(vt, e);
                        if (captureDown) {
                            // Reserve an expanded child's stream before the enclosing shade's
                            // ScrollView gets a chance to steal a slightly diagonal swipe.
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                            v.onTouchEvent(e); // preserve click/long-press while owning the stream
                        }
                        return captureDown; // grouped children must remain the gesture target
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                        if (Math.abs(dx) > slop / 3f || Math.abs(dy) > slop) moved = true;
                        if (captureDown && !swiping && !vertical && Math.abs(dy) > slop
                                && Math.abs(dy) > Math.abs(dx) * 1.35f) {
                            // It is genuinely a list scroll. Give interception back to the
                            // ScrollView; short diagonal motion remains owned by this subcard.
                            vertical = true;
                            v.cancelLongPress();
                            v.setPressed(false);
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                            return true;
                        }
                        if (!vertical && !swiping && Math.abs(dx) > Math.max(2f, slop * 0.30f)
                                && Math.abs(dx) > Math.abs(dy) * 0.65f) {
                            swiping = true;
                            swipingCard = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                            if (captureDown) {
                                MotionEvent cancel = MotionEvent.obtain(e);
                                cancel.setAction(MotionEvent.ACTION_CANCEL);
                                v.onTouchEvent(cancel);
                                cancel.recycle();
                            }
                            v.cancelLongPress();   // holding it while you swipe must not open the menu
                            v.setPressed(false);
                        }
                        if (swiping) {
                            float w = Math.max(1, v.getWidth());
                            v.setTranslationX(dx);
                            float f = Math.min(1f, Math.abs(dx) / (w * 0.8f));
                            v.setAlpha(1f - f * f * 0.85f - f * 0.1f);
                            return true;
                        }
                        if (vertical) return true;
                        if (moved) { v.cancelLongPress(); v.setPressed(false); }
                        if (captureDown && !moved) v.onTouchEvent(e);
                        return captureDown;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float vx = 0;
                        if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                        float dx = swiping ? v.getTranslationX() : e.getRawX() - x0;
                        float w = Math.max(1, v.getWidth());
                        boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                        boolean fling = Math.abs(vx) > 320 && Math.signum(vx) == Math.signum(dx)
                                && Math.abs(dx) > svc.px(4);
                        if (vertical) {
                            vertical = false;
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                            v.setPressed(false);
                            return true;
                        }
                        if (!swiping) {
                            // a flick so quick it never registered as a drag: still a dismiss, never a tap
                            if (up && fling) { fly(v, dx, vx, dismiss); return true; }
                            if (moved) { v.setPressed(false); return true; }   // swallow: no accidental open
                            if (captureDown) { v.onTouchEvent(e); return true; }
                            return false;
                        }
                        swiping = false;
                        if (up && (Math.abs(dx) > w * 0.20f || fling)) {
                            fly(v, dx, vx, dismiss);
                        } else {
                            v.animate().translationX(0).alpha(1f).setDuration(280)
                                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f))
                                    .withEndAction(new Runnable() { public void run() {
                                        swipingCard = false;
                                        if (refreshPending) refreshNotifications();
                                    }}).start();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    /**
     * Velocity in screen coordinates. The card follows the finger, so in its own coordinates
     * the finger barely moves and every flick read as ~0 px/s.
     */
    private static void track(VelocityTracker vt, MotionEvent e) {
        MotionEvent c = MotionEvent.obtain(e);
        c.setLocation(e.getRawX(), e.getRawY());
        vt.addMovement(c);
        c.recycle();
    }

    /** Off the side at the finger's speed; the list then closes the gap. */
    private void fly(final View v, float dx, float vx, final Runnable dismiss) {
        swipingCard = true;
        float w = Math.max(1, v.getWidth());
        float to = Math.signum(dx == 0 ? vx : dx) * w * 1.1f;
        long ms = (long) Math.max(100, Math.min(240, Math.abs(to - v.getTranslationX()) / Math.max(1800, Math.abs(vx)) * 1000));
        v.animate().translationX(to).alpha(0f).setDuration(ms)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() { public void run() {
                    collapseDismissedCard(v, dismiss);
                }}).start();
    }

    /** Remove once and let ChangeBounds translate siblings on the compositor—no per-frame layout. */
    private void collapseDismissedCard(final View v, final Runnable dismiss) {
        final ViewGroup parent = (ViewGroup) v.getParent();
        if (parent == null) {
            dismiss.run();
            swipingCard = false;
            return;
        }
        final LayoutTransition oldTransition = parent.getLayoutTransition();
        parent.setLayoutTransition(null);
        android.transition.ChangeBounds bounds = new android.transition.ChangeBounds();
        bounds.setDuration(330);
        bounds.setInterpolator(EMPHASIZED_EASE);
        android.transition.TransitionManager.beginDelayedTransition(parent, bounds);
        clearable.remove(v);
        entryViews.remove(v);
        expanders.remove(v);
        collapsers.remove(v);
        parent.removeView(v);
        dismiss.run();                 // listener refreshes are held by swipingCard
        root.postDelayed(new Runnable() { public void run() {
            parent.setLayoutTransition(oldTransition);
            swipingCard = false;
            if (refreshPending && root != null) root.postDelayed(new Runnable() { public void run() {
                if (attached && !swipingCard && refreshPending) refreshNotifications();
            }}, 260);
        }}, 350);
    }

    private static CharSequence text(Notification n) {
        Bundle ex = n.extras;
        if (ex == null) return null;
        Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0) {
            StringBuilder b = new StringBuilder();
            for (int i = Math.max(0, msgs.length - 3); i < msgs.length; i++) {
                if (!(msgs[i] instanceof Bundle)) continue;
                Bundle m = (Bundle) msgs[i];
                CharSequence t = m.getCharSequence("text");
                if (t == null) continue;
                CharSequence who = m.getCharSequence("sender");
                if (who == null && Build.VERSION.SDK_INT >= 28) {
                    android.app.Person p = m.getParcelable("sender_person");
                    if (p != null) who = p.getName();
                }
                if (b.length() > 0) b.append('\n');
                if (who != null) b.append(who).append(": ");
                b.append(t);
            }
            if (b.length() > 0) return b;
        }
        CharSequence big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (!TextUtils.isEmpty(big)) return big;
        CharSequence t = ex.getCharSequence(Notification.EXTRA_TEXT);
        if (!TextUtils.isEmpty(t)) return t;
        return ex.getCharSequence(Notification.EXTRA_SUB_TEXT);
    }

    private CharSequence appLabel(String pkg) {
        CharSequence l = labels.get(pkg);
        if (l != null) return l;
        try {
            PackageManager pm = svc.getPackageManager();
            l = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
        } catch (Exception e) { l = pkg; }
        labels.put(pkg, l);
        return l;
    }

    // ------------------------------------------------------------ helpers

    /** Anything that opens a screen has to put the shade away first: it would cover it. */
    private void launch(Intent i) {
        closeNow();
        svc.startFromShade(i);
    }

    /**
     * Switch a radio through the root daemon, or Shizuku as a fallback. False means the tile
     * should open Android's panel. The tiles refresh as the radio actually changes.
     */
    private boolean shell(String cmd) {
        if (!Shell.privileged()) return false;
        Shell.runResult(cmd, new Shell.ResultCallback() { public void done(Shell.Result result) {
            if (result.code != 0) svc.toast("Privileged command failed (" + result.code + ")");
            refreshTiles();
        }});
        root.postDelayed(new Runnable() { public void run() { refreshTiles(); } }, 1200);
        root.postDelayed(new Runnable() { public void run() { refreshTiles(); } }, 3000);
        return true;
    }

    /** Read the POCO's actual reverse-charging switch through the privileged channel. */
    private void refreshReverseCharging() {
        if (!Shell.privileged()) { reverseCharging = -1; return; }
        Shell.runResult("cat /sys/class/qcom-battery/reverse_chg_mode 2>/dev/null",
                new Shell.ResultCallback() { public void done(Shell.Result result) {
                    if (result.code == 0) {
                        try { reverseCharging = Integer.parseInt(result.output.trim()) == 0 ? 0 : 1; }
                        catch (Exception e) { reverseCharging = -1; }
                    } else reverseCharging = -1;
                    refreshTiles();
                }});
    }

    private String rootTool(String args) {
        return "CLASSPATH=" + svc.getApplicationInfo().sourceDir
                + " /system/bin/app_process /system/bin com.jakes.tickerbar.RootTool " + args;
    }

    private void refreshHotspot() {
        if (!RootShell.available()) { hotspot = -1; return; }
        Shell.runResult(rootTool("hotspot status"),
                new Shell.ResultCallback() { public void done(Shell.Result result) {
                    if (result.code == 0) hotspot = "on".equals(result.output.trim()) ? 1 : 0;
                    else hotspot = -1;
                    refreshTiles();
                }});
    }

    /** Opens the first of these that exists. */
    private boolean launchFirst(Intent... all) {
        PackageManager pm = svc.getPackageManager();
        for (Intent i : all) {
            if (i.resolveActivity(pm) != null) { launch(i); return true; }
        }
        return false;
    }

    /** HyperOS keeps ultra saver and reverse charging in its own battery screen. */
    private void openBattery() {
        if (!launchFirst(new Intent().setClassName("com.miui.securitycenter", "com.miui.powercenter.PowerMainActivity"),
                new Intent().setClassName("com.miui.securitycenter", "com.miui.powercenter.PowerCenter"),
                new Intent(Intent.ACTION_POWER_USAGE_SUMMARY)))
            launch(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
    }

    private Intent recorderIntent() {
        PackageManager pm = svc.getPackageManager();
        // HyperOS deliberately ships its recorder without a launcher activity.  Its public
        // settings action is the visible recorder app and works without the protected
        // READ_FRAME_BUFFER permission needed by RECORDER_ACTIVITY.
        Intent miui = new Intent("miui.intent.action.SCREENRECORDER_SETTINGS")
                .setPackage("com.miui.screenrecorder");
        if (miui.resolveActivity(pm) != null) return miui;
        for (String pkg : new String[]{"com.miui.screenrecorder", "com.samsung.android.app.screenrecorder",
                "com.google.android.apps.screenrecorder"}) {
            Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return null;
    }

    private void askWriteSettings() {
        launch(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + svc.getPackageName())));
    }

    private TextView tv(String s, int sp, int color) {
        TextView t = new TextView(svc);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView pill(String label, View.OnClickListener l) {
        TextView t = tv(label, 13, pal.onSurface);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(svc.px(16), svc.px(8), svc.px(16), svc.px(8));
        t.setBackground(ripple(round(pal.surfaceHigh, svc.px(18))));
        t.setOnClickListener(l);
        return t;
    }

    /** Notification actions are always one physical line; their shared rail owns overflow. */
    private TextView actionPill(String label, View.OnClickListener listener) {
        TextView action = pill(label, listener);
        action.setSingleLine(true);
        action.setMaxLines(1);
        return action;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private RippleDrawable ripple(Drawable content) {
        GradientDrawable mask = round(Color.WHITE, svc.px(28));
        return new RippleDrawable(ColorStateList.valueOf(Palette.alpha(pal.onSurface, 0x22)), content, mask);
    }
}
