package com.jakes.tickerbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
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
    private LinearLayout sheet, content, rowBox, gridBox, notifBox;
    private ScrollView scroll;
    private ImageView chevron;
    private SeekBar brightness;
    private boolean attached, expanded, closing;
    private float sheetH;
    private final List<Runnable> refreshers = new ArrayList<Runnable>();   // tiles, rebuilt with them
    private final List<Runnable> fixed = new ArrayList<Runnable>();        // header and brightness row
    private final HashMap<String, CharSequence> labels = new HashMap<String, CharSequence>();

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
        sheet.setTranslationY(-sheetH);
        settle(true, 0);
    }

    /** A pull from the status bar starts: the sheet follows the finger from here. */
    void beginDrag() {
        if (attach()) sheet.setTranslationY(-sheetH);
    }

    void drag(float dy) {
        if (!attached || closing) return;
        float t = Math.max(-sheetH, Math.min(0, -sheetH + dy * 1.15f));
        sheet.setTranslationY(t);
        root.setAlpha(Math.min(1f, 0.35f + (t + sheetH) / sheetH));
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
        final float target = open ? 0 : -sheetH;
        float dist = Math.abs(sheet.getTranslationY() - target);
        long ms = (long) Math.max(160, Math.min(380, dist / Math.max(1500, Math.abs(velocity)) * 1000));
        if (!open) closing = true;
        sheet.animate().cancel();
        sheet.animate().translationY(target).setDuration(ms)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        sheet.animate().setListener(null);
                        if (!open) detach();
                    }
                }).start();
        root.animate().alpha(open ? 1f : 0f).setDuration(ms).start();
    }

    private boolean attach() {
        if (attached) {
            closing = false;
            sheet.animate().cancel();
            root.animate().cancel();
            root.setAlpha(1f);
            return true;
        }
        pal = Palette.of(svc, Prefs.n(svc, Prefs.SHADE_THEME));
        int ai = Prefs.n(svc, Prefs.SHADE_ACCENT);
        if (ai > 0 && ai < ACCENTS.length) pal = pal.withAccent(ACCENTS[ai]);
        panelA = Math.round(255 * Math.max(40, Math.min(100, Prefs.n(svc, Prefs.SHADE_ALPHA))) / 100f);
        build();
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams lp = svc.overlay(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, 0);
        lp.flags = (lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) | flags;   // focus: back key closes us
        if (Build.VERSION.SDK_INT >= 31) {
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            int blur = Math.max(0, Math.min(120, Prefs.n(svc, Prefs.SHADE_BLUR)));
            if (blur > 0) lp.setBlurBehindRadius(svc.px(blur));
            else lp.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.dimAmount = 0f;
        }
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;   // inline replies
        lp.setTitle("TickerBar shade");
        try { svc.windows().addView(root, lp); } catch (Exception e) { return false; }
        attached = true;
        closing = false;
        root.setAlpha(0.35f);
        sheetH = svc.screenH();
        startTorchWatch();
        refreshAll();
        return true;
    }

    private void detach() {
        stopTorchWatch();
        if (attached) {
            try { svc.windows().removeViewImmediate(root); } catch (Exception ignored) {}
        }
        attached = false;
        closing = false;
        refreshers.clear();
        fixed.clear();
        openGroups.clear();
        openCards.clear();
        swipingCard = false;
        replying = false;
        if (mediaCtl != null) { try { mediaCtl.unregisterCallback(mediaCb); } catch (Exception ignored) {} mediaCtl = null; }
        if (root != null) root.removeCallbacks(mediaTick);
        svc.shadeClosed();
    }

    // ------------------------------------------------------------ layout

    private void build() {
        refreshers.clear();
        fixed.clear();
        root = new FrameLayout(svc) {
            private float y0, x0;
            private boolean dragging, pulledOpen;
            private VelocityTracker vt;

            @Override public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) {
                        if (expanded) setExpanded(false); else close();
                    }
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
                        if (up > slop * 2 && up > Math.abs(e.getRawX() - x0) && !scroll.canScrollVertically(1)) {
                            if (expanded) {   // stock: the first swipe up folds the controls, the next closes
                                pulledOpen = true;
                                setExpanded(false);
                                return true;
                            }
                            dragging = true;
                            return true;
                        }
                        // pulling down with the list at its top opens the full quick settings
                        if (!expanded && -up > svc.px(32) && -up > Math.abs(e.getRawX() - x0) * 1.2f
                                && !scroll.canScrollVertically(-1)) {
                            pulledOpen = true;
                            setExpanded(true);
                            return true;
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
                        if (dragging) sheet.setTranslationY(Math.min(0, e.getRawY() - y0));
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
        root.setBackgroundColor(Palette.alpha(pal.dark ? Color.BLACK : pal.bg, pal.dark ? 0x88 : 0x99));
        root.setFocusableInTouchMode(true);

        sheet = new LinearLayout(svc);
        sheet.setOrientation(LinearLayout.VERTICAL);
        root.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(svc);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        content = new LinearLayout(svc);
        content.setOrientation(LinearLayout.VERTICAL);
        // Starts at the very top: the panel's top corners follow the screen's own curve
        int corner = svc.screenCorner();
        int side = svc.px(8);
        content.setPadding(side, side, side, svc.navBar() + svc.px(28));
        content.setLayoutTransition(new LayoutTransition());
        // empty space (below the list, between cards) puts the shade away, like stock
        content.setMinimumHeight(svc.screenH());
        content.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { close(); } });
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- quick settings panel
        LinearLayout qs = new LinearLayout(svc);
        qs.setOrientation(LinearLayout.VERTICAL);
        int topR = Math.max(svc.px(32), corner - side);
        // keep the header clear of the corner curve, and of a camera cut-out in the middle
        qs.setPadding(svc.px(16) + topR / 4, Math.max(svc.px(14), topR / 3), svc.px(16) + topR / 4, svc.px(12));
        GradientDrawable qsBg = new GradientDrawable();
        qsBg.setColor(Palette.alpha(pal.surface, panelA));
        qsBg.setCornerRadii(new float[]{topR, topR, topR, topR, svc.px(32), svc.px(32), svc.px(32), svc.px(32)});
        qs.setBackground(qsBg);
        qs.setLayoutTransition(new LayoutTransition());
        qs.setClickable(true);   // taps inside the panel never fall through to "close"
        content.addView(qs);

        qs.addView(header());

        rowBox = new LinearLayout(svc);
        rowBox.setOrientation(LinearLayout.HORIZONTAL);
        rowBox.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = svc.px(14);
        qs.addView(rowBox, rlp);

        gridBox = new LinearLayout(svc);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = svc.px(14);
        qs.addView(gridBox, glp);

        qs.addView(brightnessRow());

        chevron = new ImageView(svc);
        chevron.setImageResource(R.drawable.qs_chevron);
        chevron.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        chevron.setPadding(svc.px(8), svc.px(2), svc.px(8), svc.px(2));
        chevron.setBackground(ripple(null));
        chevron.setContentDescription("Expand quick settings");
        chevron.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { setExpanded(!expanded); } });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(svc.px(56), svc.px(26));
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        clp.topMargin = svc.px(4);
        qs.addView(chevron, clp);

        // a downward fling on the panel expands it, like the second pull on stock Android
        qs.setOnTouchListener(new View.OnTouchListener() {
            float y0; boolean done;
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    y0 = e.getRawY(); done = false;
                    v.getParent().requestDisallowInterceptTouchEvent(true);   // this drag is ours, not a scroll
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !done) {
                    float dy = e.getRawY() - y0;
                    if (dy > svc.px(28) && !expanded) { done = true; setExpanded(true); }
                    else if (dy < -svc.px(28)) { done = true; if (expanded) setExpanded(false); else close(); }
                }
                return true;
            }
        });

        // ---- notifications
        notifBox = new LinearLayout(svc);
        notifBox.setOrientation(LinearLayout.VERTICAL);
        notifBox.setLayoutTransition(new LayoutTransition());
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

        TextClock clock = new TextClock(svc);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        clock.setTextColor(pal.onSurface);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        h.addView(clock);

        TextClock date = new TextClock(svc);
        date.setFormat12Hour("EEE, MMM d");
        date.setFormat24Hour("EEE, MMM d");
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        date.setTextColor(pal.onSurfaceVariant);
        date.setPadding(svc.px(12), svc.px(8), 0, 0);
        LinearLayout dateCol = new LinearLayout(svc);
        dateCol.setOrientation(LinearLayout.VERTICAL);
        dateCol.addView(date);
        final TextView batt = tv("", 13, pal.onSurfaceVariant);
        batt.setPadding(svc.px(12), 0, 0, 0);
        dateCol.addView(batt);
        h.addView(dateCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        fixed.add(new Runnable() { public void run() {
            Intent b = svc.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            int level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
            int st = b.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, 0);
            boolean charging = st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
            if (level >= 0) batt.setText(Math.round(100f * level / scale) + "%" + (charging ? "  \u26a1 Charging" : ""));
        }});

        ImageView power = new ImageView(svc);
        power.setImageResource(R.drawable.qs_power);
        power.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        power.setPadding(svc.px(10), svc.px(10), svc.px(10), svc.px(10));
        power.setBackground(ripple(round(pal.surfaceHigh, svc.px(22))));
        power.setContentDescription("Power menu");
        power.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            closeNow();
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        }});
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(svc.px(44), svc.px(44));
        plp.setMarginEnd(svc.px(8));
        h.addView(power, plp);

        ImageView gear = new ImageView(svc);
        gear.setImageResource(R.drawable.qs_settings);
        gear.setImageTintList(ColorStateList.valueOf(pal.onSurfaceVariant));
        gear.setPadding(svc.px(10), svc.px(10), svc.px(10), svc.px(10));
        gear.setBackground(ripple(round(pal.surfaceHigh, svc.px(22))));
        gear.setContentDescription("Settings");
        gear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            launch(new Intent(Settings.ACTION_SETTINGS)); }});
        h.addView(gear, new LinearLayout.LayoutParams(svc.px(44), svc.px(44)));
        return h;
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
        fixed.add(new Runnable() { public void run() {
            boolean auto = autoBrightness();
            sun.setImageResource(auto ? R.drawable.qs_brightness_auto : R.drawable.qs_brightness);
            sun.setImageTintList(ColorStateList.valueOf(auto ? pal.onPrimary : pal.primary));
            sun.setBackground(auto ? round(pal.primary, svc.px(19)) : null);
        }});

        brightness = new SeekBar(svc);
        brightness.setMax(1000);
        brightness.setProgressTintList(ColorStateList.valueOf(pal.primary));
        brightness.setThumbTintList(ColorStateList.valueOf(pal.primary));
        brightness.setProgressBackgroundTintList(ColorStateList.valueOf(pal.outline));
        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                if (!Settings.System.canWrite(svc)) { askWriteSettings(); return; }
                try {
                    Settings.System.putInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS,
                            fromSlider(p));
                } catch (Exception ignored) {}
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, svc.px(52), 1);
        r.addView(brightness, sp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                svc.px(52));
        lp.topMargin = svc.px(14);
        r.setLayoutParams(lp);
        fixed.add(new Runnable() { public void run() {
            try {
                brightness.setProgress(toSlider(Settings.System.getInt(svc.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS)));
            } catch (Exception ignored) {}
        }});
        return r;
    }

    private boolean autoBrightness() {
        return Settings.System.getInt(svc.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    // perceptual curve: the slider's halfway point is far dimmer than half the backlight
    private static int fromSlider(int p) { return Math.max(1, Math.round((float) Math.pow(p / 1000f, 2.2) * 255)); }
    private static int toSlider(int v) { return Math.round((float) Math.pow(Math.max(0, Math.min(255, v)) / 255f, 1 / 2.2) * 1000); }

    private void setExpanded(boolean on) {
        expanded = on;
        gridBox.setVisibility(on ? View.VISIBLE : View.GONE);
        rowBox.setVisibility(on ? View.GONE : View.VISIBLE);
        chevron.animate().rotation(on ? 180f : 0f).setDuration(220).start();
        chevron.setContentDescription(on ? "Collapse quick settings" : "Expand quick settings");
    }

    // ------------------------------------------------------------ tiles

    private void buildTiles() {
        rowBox.removeAllViews();
        gridBox.removeAllViews();
        List<Tile> row = new ArrayList<Tile>(), grid = new ArrayList<Tile>();
        for (int i = 0; i < TILE_IDS.length; i++) {
            int place = Prefs.n(svc, placeKey(TILE_IDS[i]));
            if (place == PLACE_HIDDEN) continue;
            Tile t = tile(TILE_IDS[i]);
            if (t == null) continue;
            tileIds.put(t, TILE_IDS[i]);
            grid.add(t);
            if (place == PLACE_BOTH && row.size() < ROW_MAX) row.add(t);
        }
        for (Tile t : row) rowBox.addView(roundTile(t),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout line = null;
        for (int i = 0; i < grid.size(); i++) {
            if (i % 2 == 0) {
                line = new LinearLayout(svc);
                line.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) lp.topMargin = svc.px(10);
                gridBox.addView(line, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, svc.px(68), 1);
            if (i % 2 == 0) lp.setMarginEnd(svc.px(10));
            line.addView(pillTile(grid.get(i)), lp);
        }
        if (grid.size() % 2 == 1 && line != null)
            line.addView(new View(svc), new LinearLayout.LayoutParams(0, svc.px(68), 1));
    }

    /** First-row tile: a round icon button, as in stock Android's collapsed quick settings. */
    private View roundTile(final Tile t) {
        final ImageView v = new ImageView(svc);
        v.setImageResource(t.icon());
        v.setPadding(svc.px(15), svc.px(15), svc.px(15), svc.px(15));
        v.setContentDescription(t.label());
        LinearLayout wrap = new LinearLayout(svc);
        wrap.setGravity(Gravity.CENTER);
        wrap.addView(v, new LinearLayout.LayoutParams(svc.px(54), svc.px(54)));
        v.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { press(x); t.tap(); refreshTiles(); } });
        v.setOnLongClickListener(openSettings(t));
        refreshers.add(new Runnable() { public void run() {
            boolean on = t.active();
            v.setBackground(ripple(round(on ? pal.primary : pal.surfaceHigh, svc.px(27))));
            v.setImageTintList(ColorStateList.valueOf(on ? pal.onPrimary : pal.onSurface));
        }});
        return wrap;
    }

    /** Expanded tile: icon, name and state in a pill, as in stock Android's full grid. */
    private View pillTile(final Tile t) {
        final LinearLayout p = new LinearLayout(svc);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(svc.px(16), 0, svc.px(12), 0);
        final ImageView ic = new ImageView(svc);
        ic.setImageResource(t.icon());
        p.addView(ic, new LinearLayout.LayoutParams(svc.px(22), svc.px(22)));
        LinearLayout text = new LinearLayout(svc);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(svc.px(12), 0, 0, 0);
        final TextView name = tv(t.label(), 14, pal.onSurface);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        final TextView state = tv("", 12, pal.onSurfaceVariant);
        state.setSingleLine(true);
        state.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);
        text.addView(state);
        p.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        p.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { press(x); t.tap(); refreshTiles(); } });
        p.setOnLongClickListener(openSettings(t));
        refreshers.add(new Runnable() { public void run() {
            boolean on = t.active();
            p.setBackground(ripple(round(on ? pal.primary : pal.surfaceHigh, svc.px(34))));
            ic.setImageTintList(ColorStateList.valueOf(on ? pal.onPrimary : pal.onSurface));
            name.setTextColor(on ? pal.onPrimary : pal.onSurface);
            state.setTextColor(on ? Palette.alpha(pal.onPrimary, 0xCC) : pal.onSurfaceVariant);
            state.setText(t.state());
        }});
        return p;
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
                public void tap() { launch(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); }
            };
            case "data": return new Tile() {
                public int icon() { return R.drawable.qs_data; }
                public String label() { return "Mobile data"; }
                public boolean active() { return false; }
                public String state() { return "Tap for options"; }
                public void tap() { launch(new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); }
            };
            case "bluetooth": return new Tile() {
                public int icon() { return R.drawable.qs_bluetooth; }
                public String label() { return "Bluetooth"; }
                public boolean active() {
                    try {
                        android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager)
                                svc.getSystemService(Context.BLUETOOTH_SERVICE);
                        return bm != null && bm.getAdapter() != null && bm.getAdapter().isEnabled();
                    } catch (Exception e) { return false; }
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() { launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            };
            case "flashlight":
                if (torchId == null) return null;
                return new Tile() {
                    public int icon() { return R.drawable.qs_flashlight; }
                    public String label() { return "Flashlight"; }
                    public boolean active() { return torchOn; }
                    public String state() { return torchOn ? "On" : "Off"; }
                    public void tap() {
                        try { cams.setTorchMode(torchId, !torchOn); torchOn = !torchOn; } catch (Exception ignored) {}
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
                public void tap() { launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
            };
            case "airplane": return new Tile() {
                public int icon() { return R.drawable.qs_airplane; }
                public String label() { return "Airplane mode"; }
                public boolean active() {
                    return Settings.Global.getInt(svc.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() { launch(new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)); }
            };
            case "hotspot": return new Tile() {
                public int icon() { return R.drawable.qs_hotspot; }
                public String label() { return "Hotspot"; }
                public boolean active() {
                    try {   // no public getter; this hidden one is readable on most builds
                        Object wm = svc.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        return (Boolean) wm.getClass().getMethod("isWifiApEnabled").invoke(wm);
                    } catch (Throwable t) { return false; }
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() {
                    if (!launchFirst(new Intent("android.settings.TETHER_SETTINGS"),
                            new Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")))
                        launch(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                }
            };
            case "recorder": return new Tile() {
                public int icon() { return R.drawable.qs_recorder; }
                public String label() { return "Screen recorder"; }
                public boolean active() { return false; }
                public String state() { return "Tap to start"; }
                public void tap() {
                    Intent i = recorderIntent();
                    if (i != null) launch(i);
                    else svc.toast("No screen recorder app found");
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
                public boolean active() { return false; }
                public String state() { return "Battery settings"; }
                public void tap() { openBattery(); }
            };
            case "reverse": return new Tile() {
                public int icon() { return R.drawable.qs_reverse; }
                public String label() { return "Reverse charging"; }
                public boolean active() { return false; }
                public String state() { return "Battery settings"; }
                public void tap() { openBattery(); }
            };
            case "battery": return new Tile() {
                public int icon() { return R.drawable.qs_battery; }
                public String label() { return "Battery Saver"; }
                public boolean active() {
                    PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
                    return pm != null && pm.isPowerSaveMode();
                }
                public String state() { return active() ? "On" : "Off"; }
                public void tap() { launch(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)); }
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

    private final HashSet<String> openGroups = new HashSet<String>();    // expanded groups
    private final HashSet<String> openCards = new HashSet<String>();     // expanded single notifications
    private boolean swipingCard, refreshPending, replying;
    private NotificationListenerService.RankingMap rankMap;

    /** Called when notifications change while the shade is open. */
    void refreshNotifications() {
        if (!attached || notifBox == null) return;
        // never rebuild under a finger, or while a reply is being typed
        if (swipingCard || replying) { refreshPending = true; return; }
        refreshPending = false;
        notifBox.removeAllViews();
        clearable.clear();
        rankMap = TickerListener.ranking();
        StatusBarNotification[] all = TickerListener.active();

        MediaController player = playerSession();
        if (player != null) {
            View m = mediaCard(player);
            if (m != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = svc.px(10);
                notifBox.addView(m, lp);
            }
        }

        List<Entry> list = entries(all, player);
        boolean anyClearable = false;
        for (Entry en : list) for (StatusBarNotification k : en.kids) if (k.isClearable()) anyClearable = true;

        if (list.isEmpty()) {
            if (player == null) {
                TextView none = tv(all == null ? "Notification access is off" : "No notifications", 14, pal.onSurfaceVariant);
                none.setGravity(Gravity.CENTER);
                none.setPadding(0, svc.px(28), 0, svc.px(28));
                notifBox.addView(none);
            }
        } else {
            int[] count = new int[3];
            for (Entry en : list) count[en.section]++;
            boolean several = (count[0] > 0 ? 1 : 0) + (count[1] > 0 ? 1 : 0) + (count[2] > 0 ? 1 : 0) > 1;
            String[] heads = {"Conversations", "Notifications", "Silent"};
            for (int sec = 0; sec < 3; sec++) {
                if (count[sec] == 0) continue;
                if (several) notifBox.addView(sectionHeader(heads[sec], sec == 2 ? list : null));
                for (Entry en : list) {
                    if (en.section != sec) continue;
                    View c = en.kids.size() >= 2 ? groupCard(en) : card(en.kids.get(0), false);
                    if (c == null) continue;
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.bottomMargin = svc.px(3);
                    notifBox.addView(c, lp);
                }
            }
        }

        if (anyClearable) {
            LinearLayout foot = new LinearLayout(svc);
            foot.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            foot.setPadding(svc.px(4), svc.px(10), svc.px(4), 0);
            foot.addView(pill("Clear all", new View.OnClickListener() { public void onClick(View v) { clearAll(); }}));
            notifBox.addView(foot);
        }
    }

    private final List<View> clearable = new ArrayList<View>();   // swipeable entries, in list order

    /**
     * Stock Clear all: the entries sweep off to the side one after another from the bottom,
     * then the shade rolls up.
     */
    private static final long SWEEP_MS = 220;

    private void clearAll() {
        swipingCard = true;   // hold list rebuilds while they animate out
        List<View> views = new ArrayList<View>(clearable);
        long delay = 0;
        for (int i = views.size() - 1; i >= 0; i--) {
            View v = views.get(i);
            if (v.getParent() == null) continue;
            v.animate().translationX(v.getWidth() * 1.1f).alpha(0f).setStartDelay(delay).setDuration(SWEEP_MS)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f)).start();
            delay += SWEEP_MS / 10;   // the next one leaves when this one is ~10% of the way out
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
    private List<Entry> entries(StatusBarNotification[] all, MediaController player) {
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
            if (player != null && s.getPackageName().equals(player.getPackageName()) && isMedia(s)) continue;
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

    private SeekBar mediaSeek;
    private TextView mediaPos, mediaDur;
    private MediaController mediaCtl;
    private final MediaController.Callback mediaCb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(android.media.session.PlaybackState s) { refreshNotifications(); }
        @Override public void onMetadataChanged(android.media.MediaMetadata m) { refreshNotifications(); }
    };
    private final Runnable mediaTick = new Runnable() { public void run() {
        if (!attached || mediaCtl == null || mediaSeek == null) return;
        android.media.session.PlaybackState st = mediaCtl.getPlaybackState();
        if (st != null && !mediaSeek.isPressed()) {
            long pos = position(st);
            mediaSeek.setProgress((int) Math.min(Integer.MAX_VALUE, pos));
            mediaPos.setText(DateUtils.formatElapsedTime(pos / 1000));
        }
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

    /** Playing first, then paused; only sessions that have something to show. */
    private MediaController playerSession() {
        MediaController paused = null;
        for (MediaController mc : svc.mediaSessions()) {
            if (Prefs.hidden(svc, mc.getPackageName())) continue;
            android.media.session.PlaybackState st = mc.getPlaybackState();
            if (st == null || mc.getMetadata() == null) continue;
            int s = st.getState();
            if (s == android.media.session.PlaybackState.STATE_PLAYING
                    || s == android.media.session.PlaybackState.STATE_BUFFERING) return mc;
            if (s == android.media.session.PlaybackState.STATE_PAUSED && paused == null) paused = mc;
        }
        return paused;
    }

    private View mediaCard(final MediaController mc) {
        android.media.MediaMetadata md = mc.getMetadata();
        if (md == null) return null;
        if (mediaCtl != mc) {
            if (mediaCtl != null) try { mediaCtl.unregisterCallback(mediaCb); } catch (Exception ignored) {}
            mediaCtl = mc;
            mc.registerCallback(mediaCb, root.getHandler());
        }
        CharSequence title = md.getText(android.media.MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) title = md.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        CharSequence artist = md.getText(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(artist)) artist = md.getText(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        android.graphics.Bitmap art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        long dur = md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        android.media.session.PlaybackState st = mc.getPlaybackState();
        final boolean playing = st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING;

        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(svc.px(16), svc.px(16), svc.px(16), svc.px(12));
        box.setBackground(ripple(round(Palette.alpha(pal.primaryContainer, panelA), svc.px(28))));
        box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            PendingIntent pi = mc.getSessionActivity();
            if (pi != null) svc.sendPending(pi); else svc.startFromShade(launchIntent(mc.getPackageName()));
            close();
        }});

        LinearLayout top = new LinearLayout(svc);
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (art != null) {
            ImageView a = new ImageView(svc);
            a.setImageBitmap(art);
            a.setScaleType(ImageView.ScaleType.CENTER_CROP);
            a.setClipToOutline(true);
            a.setBackground(round(pal.surfaceHigh, svc.px(14)));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(svc.px(64), svc.px(64));
            alp.setMarginEnd(svc.px(14));
            top.addView(a, alp);
        }
        LinearLayout words = new LinearLayout(svc);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(TextUtils.isEmpty(title) ? appLabel(mc.getPackageName()).toString() : title.toString(),
                16, pal.onPrimaryContainer);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(t);
        TextView ar = tv((TextUtils.isEmpty(artist) ? "" : artist + "  ·  ") + appLabel(mc.getPackageName()),
                13, Palette.alpha(pal.onPrimaryContainer, 0xBB));
        ar.setSingleLine(true);
        ar.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(ar);
        top.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(top);

        if (dur > 0) {
            LinearLayout prog = new LinearLayout(svc);
            prog.setGravity(Gravity.CENTER_VERTICAL);
            mediaPos = tv("", 11, Palette.alpha(pal.onPrimaryContainer, 0xBB));
            mediaDur = tv(DateUtils.formatElapsedTime(dur / 1000), 11, Palette.alpha(pal.onPrimaryContainer, 0xBB));
            mediaSeek = new SeekBar(svc);
            mediaSeek.setMax((int) Math.min(Integer.MAX_VALUE, dur));
            mediaSeek.setProgressTintList(ColorStateList.valueOf(pal.primary));
            mediaSeek.setThumbTintList(ColorStateList.valueOf(pal.primary));
            mediaSeek.setProgressBackgroundTintList(ColorStateList.valueOf(Palette.alpha(pal.onPrimaryContainer, 0x55)));
            mediaSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (user) mediaPos.setText(DateUtils.formatElapsedTime(p / 1000));
                }
                public void onStartTrackingTouch(SeekBar s) { s.getParent().requestDisallowInterceptTouchEvent(true); }
                public void onStopTrackingTouch(SeekBar s) { mc.getTransportControls().seekTo(s.getProgress()); }
            });
            prog.addView(mediaPos);
            prog.addView(mediaSeek, new LinearLayout.LayoutParams(0, svc.px(36), 1));
            prog.addView(mediaDur);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            pl.topMargin = svc.px(8);
            box.addView(prog, pl);
            root.removeCallbacks(mediaTick);
            root.post(mediaTick);
        } else {
            mediaSeek = null;
        }

        LinearLayout ctl = new LinearLayout(svc);
        ctl.setGravity(Gravity.CENTER);
        ctl.addView(mediaButton(R.drawable.qs_prev, "Previous", false, new Runnable() { public void run() {
            mc.getTransportControls().skipToPrevious(); }}));
        ctl.addView(mediaButton(playing ? R.drawable.qs_pause : R.drawable.qs_play, playing ? "Pause" : "Play", true,
                new Runnable() { public void run() {
                    if (playing) mc.getTransportControls().pause(); else mc.getTransportControls().play(); }}));
        ctl.addView(mediaButton(R.drawable.qs_next, "Next", false, new Runnable() { public void run() {
            mc.getTransportControls().skipToNext(); }}));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = svc.px(4);
        box.addView(ctl, cl);
        return box;
    }

    private View mediaButton(int icon, String desc, boolean main, final Runnable r) {
        ImageView b = new ImageView(svc);
        b.setImageResource(icon);
        b.setContentDescription(desc);
        int size = svc.px(main ? 56 : 48);
        b.setPadding(svc.px(main ? 15 : 12), svc.px(main ? 15 : 12), svc.px(main ? 15 : 12), svc.px(main ? 15 : 12));
        b.setImageTintList(ColorStateList.valueOf(main ? pal.onPrimary : pal.onPrimaryContainer));
        b.setBackground(ripple(main ? round(pal.primary, size / 2) : null));
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { press(v); r.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(svc.px(10));
        lp.setMarginEnd(svc.px(10));
        b.setLayoutParams(lp);
        return b;
    }

    // ------------------------------------------------------------ groups and cards

    /** A stock-style group: collapsed it previews the latest messages; expanded, each is its own. */
    private View groupCard(final Entry en) {
        final StatusBarNotification lead = en.kids.get(0);
        final boolean open = openGroups.contains(en.group);
        final LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(svc.px(16), svc.px(14), svc.px(8), svc.px(10));
        box.setBackground(ripple(round(Palette.alpha(pal.surface, panelA), svc.px(24))));

        LinearLayout top = appLine(lead, en.kids.size());
        top.addView(chevron(open, new Runnable() { public void run() {
            if (open) openGroups.remove(en.group); else openGroups.add(en.group);
            refreshNotifications();
        }}), new LinearLayout.LayoutParams(svc.px(32), svc.px(32)));
        box.addView(top);

        if (!open) {
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
                TextView row = tv("", 14, pal.onSurface);
                row.setText(line);
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.END);
                row.setPadding(0, svc.px(i == 0 ? 8 : 2), svc.px(8), 0);
                box.addView(row);
            }
            if (en.kids.size() > 2) {
                TextView more = tv("+" + (en.kids.size() - 2) + " more", 13, pal.onSurfaceVariant);
                more.setPadding(0, svc.px(4), 0, 0);
                box.addView(more);
            }
            // tapping the closed group opens the app, as stock does
            box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
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
        } else {
            box.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                openGroups.remove(en.group);
                refreshNotifications();
            }});
            for (StatusBarNotification k : en.kids) {
                View c = card(k, true);
                if (c == null) continue;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = svc.px(6);
                lp.setMarginEnd(svc.px(8));
                box.addView(c, lp);
            }
        }
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
        chev.setPadding(svc.px(6), svc.px(6), svc.px(6), svc.px(6));
        chev.setBackground(ripple(round(pal.surfaceHigh, svc.px(16))));
        chev.setContentDescription(open ? "Collapse" : "Expand");
        chev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggle.run(); } });
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
                p.setText(armed);
                p.setTextColor(0xFFFFFFFF);
                p.setBackground(ripple(round(pal.dark ? 0xFFB3261E : 0xFFD32F2F, svc.px(18))));
                p.postDelayed(new Runnable() { public void run() {
                    p.setTag(null);
                    p.setText(label);
                    p.setTextColor(pal.onSurface);
                    p.setBackground(ripple(round(pal.surfaceHigh, svc.px(18))));
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
        final boolean open = openCards.contains(s.getKey());

        final LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = null;
        if (child) {
            card.setPadding(svc.px(14), svc.px(10), svc.px(14), svc.px(10));
            card.setBackground(ripple(round(pal.surfaceHigh, svc.px(18))));
        } else {
            card.setPadding(svc.px(16), svc.px(14), svc.px(16), svc.px(12));
            card.setBackground(ripple(round(Palette.alpha(pal.surface, panelA), svc.px(24))));
            top = appLine(s, 1);
            card.addView(top);
        }

        // title + text, with the sender's picture on the right when there is one
        LinearLayout mid = new LinearLayout(svc);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(svc);
        words.setOrientation(LinearLayout.VERTICAL);
        if (!TextUtils.isEmpty(title)) {
            TextView t = tv(title.toString(), 15, pal.onSurface);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(t);
        }
        if (!TextUtils.isEmpty(body)) {
            TextView b = tv(body.toString(), 14, pal.onSurfaceVariant);
            b.setMaxLines(open ? 30 : child ? 3 : 4);
            b.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(b);
        }
        mid.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Icon large = n.getLargeIcon();
        if (large != null && !open) {
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
                    mid.addView(li, lp);
                }
            } catch (Exception ignored) {}
        }
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (!child) mp.topMargin = svc.px(6);
        card.addView(mid, mp);

        // expand: the whole text, and the picture for picture notifications
        Drawable picture = picture(n);
        boolean expandable = picture != null || (body != null && (body.length() > 110 || body.toString().contains("\n")));
        if (expandable && top != null) {
            top.addView(chevron(open, new Runnable() { public void run() {
                if (open) openCards.remove(s.getKey()); else openCards.add(s.getKey());
                refreshNotifications();
            }}), new LinearLayout.LayoutParams(svc.px(32), svc.px(32)));
        }
        if (open && picture != null) {
            ImageView pic = new ImageView(svc);
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

        // smart replies the system suggested for this message
        final Notification.Action replyA = replyAction(n);
        NotificationListenerService.Ranking r = rank(s.getKey());
        if (replyA != null && r != null && Build.VERSION.SDK_INT >= 29) {
            List<CharSequence> smart = r.getSmartReplies();
            if (smart != null && !smart.isEmpty()) {
                LinearLayout chips = new LinearLayout(svc);
                chips.setPadding(0, svc.px(8), 0, 0);
                int shown = 0;
                for (final CharSequence q : smart) {
                    if (shown++ >= 3) break;
                    TextView c = pill(q.toString(), new View.OnClickListener() { public void onClick(View v) {
                        reply(replyA, q, true);
                    }});
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.setMarginEnd(svc.px(8));
                    chips.addView(c, lp);
                }
                android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(svc);
                hs.setHorizontalScrollBarEnabled(false);
                hs.addView(chips);
                card.addView(hs);
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

        // actions: replies open the inline box; the rest fire straight away
        if (n.actions != null && n.actions.length > 0) {
            LinearLayout acts = new LinearLayout(svc);
            acts.setPadding(0, svc.px(8), 0, 0);
            int shown = 0;
            for (final Notification.Action a : n.actions) {
                if (a == null || TextUtils.isEmpty(a.title) || a.actionIntent == null || shown >= 3) continue;
                final boolean isReply = a == replyA;
                TextView b = pill(a.title.toString(), new View.OnClickListener() { public void onClick(View v) {
                    if (isReply) {
                        replyBox.setVisibility(View.VISIBLE);
                        replying = true;
                        input.requestFocus();
                        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                                svc.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(input, 0);
                        return;
                    }
                    svc.sendPending(a.actionIntent);
                    close();
                }});
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(svc.px(8));
                acts.addView(b, lp);
                shown++;
            }
            if (shown > 0) card.addView(acts);
        }
        card.addView(replyBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        send.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            CharSequence msg = input.getText();
            if (replyA == null || TextUtils.isEmpty(msg)) return;
            reply(replyA, msg.toString(), false);
            input.setText("");
            replyBox.setVisibility(View.GONE);
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    svc.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            replying = false;
            if (refreshPending) refreshNotifications();
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
        if (s.isClearable()) swipeToDismiss(card, new Runnable() { public void run() { TickerListener.cancel(s.getKey()); } });
        return card;
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
        card.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0; boolean swiping, moved; VelocityTracker vt;
            public boolean onTouch(View v, MotionEvent e) {
                int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
                if (vt != null && e.getActionMasked() != MotionEvent.ACTION_DOWN) vt.addMovement(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); swiping = false; moved = false;
                        v.animate().cancel();
                        if (vt != null) vt.recycle();
                        vt = VelocityTracker.obtain(); vt.addMovement(e);
                        return false;   // let the click / scroll machinery see it too
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - x0, dy = e.getRawY() - y0;
                        if (Math.abs(dx) > slop / 2f) moved = true;   // sideways at all: not a tap
                        if (!swiping && Math.abs(dx) > slop * 0.7f && Math.abs(dx) > Math.abs(dy)) {
                            swiping = true;
                            swipingCard = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
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
                        if (moved) { v.cancelLongPress(); v.setPressed(false); }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float vx = 0;
                        if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                        float dx = swiping ? v.getTranslationX() : e.getRawX() - x0;
                        float w = Math.max(1, v.getWidth());
                        boolean up = e.getActionMasked() == MotionEvent.ACTION_UP;
                        boolean fling = Math.abs(vx) > 650 && Math.signum(vx) == Math.signum(dx)
                                && Math.abs(dx) > svc.px(12);
                        if (!swiping) {
                            // a flick so quick it never registered as a drag: still a dismiss, never a tap
                            if (up && fling) { fly(v, dx, vx, dismiss); return true; }
                            if (moved) { v.setPressed(false); return true; }   // swallow: no accidental open
                            return false;
                        }
                        swiping = false;
                        if (up && (Math.abs(dx) > w * 0.35f || fling)) {
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

    /** Off the side at the finger's speed; the list then closes the gap. */
    private void fly(final View v, float dx, float vx, final Runnable dismiss) {
        swipingCard = true;
        float w = Math.max(1, v.getWidth());
        float to = Math.signum(dx == 0 ? vx : dx) * w * 1.1f;
        long ms = (long) Math.max(100, Math.min(240, Math.abs(to - v.getTranslationX()) / Math.max(1800, Math.abs(vx)) * 1000));
        v.animate().translationX(to).alpha(0f).setDuration(ms)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() { public void run() {
                    swipingCard = false;
                    ViewGroup parent = (ViewGroup) v.getParent();
                    if (parent != null) parent.removeView(v);
                    dismiss.run();
                    if (refreshPending) refreshNotifications();
                }}).start();
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
