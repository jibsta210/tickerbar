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
        pal = Palette.of(svc);
        build();
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams lp = svc.overlay(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, 0);
        lp.flags = (lp.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) | flags;   // focus: back key closes us
        if (Build.VERSION.SDK_INT >= 31) {
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(svc.px(36));
            lp.dimAmount = 0f;
        }
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
        svc.shadeClosed();
    }

    // ------------------------------------------------------------ layout

    private void build() {
        refreshers.clear();
        fixed.clear();
        root = new FrameLayout(svc) {
            private float y0, x0;
            private boolean dragging;
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
                        y0 = e.getRawY(); x0 = e.getRawX(); dragging = false;
                        if (vt != null) vt.recycle();
                        vt = VelocityTracker.obtain();
                        vt.addMovement(e);
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        if (vt != null) vt.addMovement(e);
                        float up = y0 - e.getRawY();
                        int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
                        if (up > slop * 2 && up > Math.abs(e.getRawX() - x0) && !scroll.canScrollVertically(1)) {
                            dragging = true;
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
                        if (dragging) {
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
        content.setPadding(svc.px(14), svc.statusBar() + svc.px(10), svc.px(14), svc.navBar() + svc.px(28));
        content.setLayoutTransition(new LayoutTransition());
        // empty space (below the list, between cards) puts the shade away, like stock
        content.setMinimumHeight(svc.screenH());
        content.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { close(); } });
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- quick settings panel
        LinearLayout qs = new LinearLayout(svc);
        qs.setOrientation(LinearLayout.VERTICAL);
        qs.setPadding(svc.px(16), svc.px(14), svc.px(16), svc.px(12));
        qs.setBackground(round(Palette.alpha(pal.surface, 0xF2), svc.px(32)));
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
                    if (dy > svc.px(48) && !expanded) { done = true; setExpanded(true); }
                    else if (dy < -svc.px(48)) { done = true; if (expanded) setExpanded(false); else close(); }
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
        h.addView(date, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

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

    /** Called when notifications change while the shade is open. */
    void refreshNotifications() {
        if (!attached || notifBox == null) return;
        notifBox.removeAllViews();
        StatusBarNotification[] all = TickerListener.active();
        List<StatusBarNotification> list = order(all);
        boolean anyClearable = false;
        for (StatusBarNotification s : list) if (s.isClearable()) { anyClearable = true; break; }

        if (list.isEmpty()) {
            TextView none = tv(all == null ? "Notification access is off" : "No notifications", 14, pal.onSurfaceVariant);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, svc.px(28), 0, svc.px(28));
            notifBox.addView(none);
        } else {
            for (StatusBarNotification s : list) {
                View c = card(s);
                if (c == null) continue;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = svc.px(3);
                notifBox.addView(c, lp);
            }
        }

        LinearLayout foot = new LinearLayout(svc);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.setPadding(svc.px(4), svc.px(10), svc.px(4), 0);
        TextView manage = pill("Manage", new View.OnClickListener() { public void onClick(View v) {
            launch(new Intent(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS)); }});
        foot.addView(manage);
        foot.addView(new View(svc), new LinearLayout.LayoutParams(0, 1, 1));
        if (anyClearable) {
            foot.addView(pill("Clear all", new View.OnClickListener() { public void onClick(View v) {
                TickerListener.cancelAll();
                close();
            }}));
        }
        notifBox.addView(foot);
    }

    /** Ranking order, group summaries dropped when their children are present. */
    private List<StatusBarNotification> order(StatusBarNotification[] all) {
        List<StatusBarNotification> out = new ArrayList<StatusBarNotification>();
        if (all == null) return out;
        HashMap<String, StatusBarNotification> byKey = new HashMap<String, StatusBarNotification>();
        HashSet<String> groupsWithChildren = new HashSet<String>();
        for (StatusBarNotification s : all) {
            byKey.put(s.getKey(), s);
            boolean summary = (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
            if (!summary && s.getGroupKey() != null) groupsWithChildren.add(s.getGroupKey());
        }
        List<String> keys = new ArrayList<String>();
        NotificationListenerService.RankingMap rm = TickerListener.ranking();
        if (rm != null) for (String k : rm.getOrderedKeys()) if (byKey.containsKey(k)) keys.add(k);
        for (StatusBarNotification s : all) if (!keys.contains(s.getKey())) keys.add(s.getKey());
        for (String k : keys) {
            StatusBarNotification s = byKey.get(k);
            boolean summary = (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
            if (summary && groupsWithChildren.contains(s.getGroupKey())) continue;
            if (svc.getPackageName().equals(s.getPackageName()) && !s.isClearable()) continue;
            if (Prefs.hidden(svc, s.getPackageName())) continue;
            out.add(s);
        }
        return out;
    }

    private View card(final StatusBarNotification s) {
        final Notification n = s.getNotification();
        Bundle ex = n.extras;
        CharSequence title = ex == null ? null : ex.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence body = text(n);
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(body)) return null;

        final LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(svc.px(16), svc.px(14), svc.px(16), svc.px(12));
        card.setBackground(ripple(round(Palette.alpha(pal.surface, 0xF2), svc.px(24))));

        // app line
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
        TextView app = tv(appLabel(s.getPackageName()) + "  ·  "
                + DateUtils.getRelativeTimeSpanString(s.getPostTime(), System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE), 12, pal.onSurfaceVariant);
        app.setPadding(svc.px(8), 0, 0, 0);
        app.setSingleLine(true);
        top.addView(app, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);

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
            b.setMaxLines(4);
            b.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(b);
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
                    mid.addView(li, lp);
                }
            } catch (Exception ignored) {}
        }
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = svc.px(6);
        card.addView(mid, mp);

        // actions (replies need the system shade's text box, so those open the notification)
        if (n.actions != null && n.actions.length > 0) {
            LinearLayout acts = new LinearLayout(svc);
            acts.setPadding(0, svc.px(8), 0, 0);
            int shown = 0;
            for (final Notification.Action a : n.actions) {
                if (a == null || TextUtils.isEmpty(a.title) || a.actionIntent == null || shown >= 3) continue;
                final boolean reply = a.getRemoteInputs() != null && a.getRemoteInputs().length > 0;
                TextView b = pill(a.title.toString(), new View.OnClickListener() { public void onClick(View v) {
                    if (reply) { svc.closeShadeAndOpenSystem(); return; }
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

        card.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (n.contentIntent != null) svc.sendPending(n.contentIntent);
            if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) TickerListener.cancel(s.getKey());
            close();
        }});
        // long-press: never show this app here (or on the ticker) again
        card.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            Prefs.setHidden(svc, s.getPackageName(), true);
            svc.toast("Hidden: " + appLabel(s.getPackageName()) + " \u2014 undo in TickerBar \u2192 Hidden apps");
            refreshNotifications();
            return true;
        }});
        if (s.isClearable()) swipeToDismiss(card, s.getKey());
        return card;
    }

    private void swipeToDismiss(final View card, final String key) {
        card.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0; boolean swiping; VelocityTracker vt;
            public boolean onTouch(View v, MotionEvent e) {
                int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); swiping = false;
                        vt = VelocityTracker.obtain(); vt.addMovement(e);
                        return false;   // let the click / scroll machinery see it too
                    case MotionEvent.ACTION_MOVE: {
                        if (vt != null) vt.addMovement(e);
                        float dx = e.getRawX() - x0;
                        if (!swiping && Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(e.getRawY() - y0) * 1.5f) {
                            swiping = true;
                            v.getParent().requestDisallowInterceptTouchEvent(true);
                            v.setPressed(false);
                        }
                        if (swiping) {
                            v.setTranslationX(dx);
                            v.setAlpha(1f - Math.min(0.8f, Math.abs(dx) / v.getWidth()));
                            return true;
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (!swiping) { if (vt != null) { vt.recycle(); vt = null; } return false; }
                        swiping = false;
                        float vx = 0;
                        if (vt != null) { vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                        float dx = v.getTranslationX();
                        if (Math.abs(dx) > v.getWidth() * 0.35f || Math.abs(vx) > 1400 && Math.signum(vx) == Math.signum(dx)) {
                            v.animate().translationX(Math.signum(dx) * v.getWidth()).alpha(0f).setDuration(160)
                                    .withEndAction(new Runnable() { public void run() {
                                        TickerListener.cancel(key);
                                        notifBox.removeView(card);
                                    }}).start();
                        } else {
                            v.animate().translationX(0).alpha(1f).setDuration(180).start();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
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
