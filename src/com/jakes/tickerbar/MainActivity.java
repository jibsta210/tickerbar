package com.jakes.tickerbar;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings, built from framework views only, coloured from the system's Material You palette. */
public class MainActivity extends Activity {

    static final String EXTRA_UPDATED = "updated";

    private boolean resumed;
    private final android.os.Handler updateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable autoUpdate = new Runnable() { public void run() {
        if (!resumed || !Updater.ready(MainActivity.this)) return;
        if (Updater.resumeConfirmation(MainActivity.this)) return;
        if (Updater.autoCheck(MainActivity.this, new Updater.Cb() {
            public void msg(String m) { updateLine.setText(m); }
        })) updateLine.setText("Checking for updates…");
    }};
    private final Runnable rootStatusUpdate = new Runnable() { public void run() {
        if (!resumed) return;
        refreshRootStatus();
        updateHandler.postDelayed(this, 1_000);
    }};

    private Palette pal;
    private LinearLayout col;
    private TextView updateLine;
    private TextView rootLine;
    private TextView chipAcc, chipNotif;
    private View cardPreview;
    private ScrollView sv;
    private android.widget.FrameLayout page;
    // each settings section is a heading + card; the menu shows one at a time
    private final java.util.List<View[]> sections = new java.util.ArrayList<View[]>();
    private final java.util.List<String> sectionNames = new java.util.ArrayList<String>();
    private View drawer;
    private Object backCb;
    private static final String SECTION = "settings_section";
    private final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    // ================================================================= screen

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Prefs.migrate(this);
        Shortcuts.publish(this);
        RootBootstrap.start(this);
        pal = Palette.of(this);

        sv = new ScrollView(this);
        sv.setClipToPadding(true);   // nothing scrolls under the status bar icons
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(12), dp(18), dp(40));
        sv.addView(col);
        page = new android.widget.FrameLayout(this);
        page.setBackgroundColor(pal.bg);
        page.addView(sv);
        setContentView(page);
        edgeToEdge(sv);   // the scroller takes the insets; the menu runs edge to edge

        header();

        // ---------------------------------------------------------- ticker
        LinearLayout c = card("Ticker");
        switchRow(c, "Ticker", "Show notifications along the status bar", Prefs.TICKER_ON);
        choiceRow(c, "Style", Prefs.STYLE, new int[]{0, 1},
                new String[]{"Classic bar", "Heads-up card"});
        choiceRow(c, "Lines", Prefs.LINES, new int[]{1, 2, 3},
                new String[]{"1 · title and text", "2 · title / text", "3 · app / title / text"});
        numberRow(c, "Text size", Prefs.TEXT, "sp");
        numberRow(c, "Minimum show time", Prefs.DWELL, "ms");
        numberRow(c, "Background opacity", Prefs.OPACITY, "%");
        switchRow(c, "Hop around the camera", "Text jumps the cut-out instead of hiding behind it", Prefs.HOP);
        numberRow(c, "Gap around the camera", Prefs.HOP_GAP, "px");
        switchRow(c, "Follow the rounded corners", "Classic bar: keep text clear of the screen's curve", Prefs.AUTO_PAD);
        numberRow(c, "Side padding when not following", Prefs.PAD, "px");
        numberRow(c, "Bar height  · 0 = auto", Prefs.HEIGHT, "px");
        switchRow(c, "Queue notifications", "Off: the newest one replaces what's showing", Prefs.QUEUE);
        switchRow(c, "Skip silent notifications", null, Prefs.SKIP_SILENT);
        switchRow(c, "Skip ongoing ones", "Media, downloads, running services", Prefs.SKIP_ONGOING);
        choiceRow(c, "While you're in that app", Prefs.FRONT_MODE, new int[]{1, 2, 0},
                new String[]{"Skip what it clears", "Hide all its notifications", "Show everything"});
        textRow(c, "Ignored apps", "Package names, comma separated", Prefs.IGNORE);

        // ---------------------------------------------------------- motion
        c = card("Motion");
        choiceRow(c, "Entry", Prefs.ANIM_IN, new int[]{4, 5, 2, 3, 1, 0},
                new String[]{"3D flip", "Flip down (hinge)", "Slide down", "Slide in from right", "Fade", "None"});
        choiceRow(c, "Exit", Prefs.ANIM_OUT, new int[]{4, 5, 2, 3, 1, 0},
                new String[]{"3D flip away", "Flip up (hinge)", "Slide up", "Slide out left", "Fade", "None"});
        numberRow(c, "Animation duration", Prefs.ANIM_MS, "ms");
        choiceRow(c, "Scrolling", Prefs.SCROLL_MODE,
                new int[]{TickerView.MODE_REVEAL, TickerView.MODE_LOOP, TickerView.MODE_OFF},
                new String[]{"Reveal once, then hold", "Continuous loop", "Off"});
        numberRow(c, "Scroll speed", Prefs.SPEED, "px/s");
        numberRow(c, "Pause before scrolling", Prefs.DELAY, "ms");

        // ---------------------------------------------------------- wallet
        c = card("Wallet card");
        cardPreview(c);
        switchRow(c, "Wallet card", "A card peeking up from the bottom edge", Prefs.SWIPE_ON);
        choiceRow(c, "Card look", Prefs.CARD_STYLE, CardArt.IDS, CardArt.NAMES);
        textRow(c, "Label on the card", "Optional, e.g. Aeroplan", Prefs.CARD_LABEL);
        switchRow(c, "On the home screen", null, Prefs.CARD_HOME);
        switchRow(c, "On the lock screen", "Asks you to unlock first", Prefs.CARD_LOCK);
        switchRow(c, "Open straight to your default card", "Google Wallet", Prefs.WALLET_QUICK);
        choiceRow(c, "Position", Prefs.SWIPE_POS, new int[]{1, 0, 2}, new String[]{"Centre", "Left", "Right"});
        choiceRow(c, "Trigger", Prefs.SWIPE_TRIGGER, new int[]{2, 0, 1},
                new String[]{"Swipe up", "Tap or swipe up", "Tap"});
        switchRow(c, "Show the card", "Off: an invisible swipe area", Prefs.SWIPE_PILL);
        numberRow(c, "Card opacity", Prefs.CARD_OPACITY, "%");
        numberRow(c, "Card peek  · 0 = auto", Prefs.CARD_PEEK, "px");
        numberRow(c, "Width", Prefs.SWIPE_W, "%");
        numberRow(c, "Swipe area height  · 0 = nav bar", Prefs.SWIPE_H, "px");
        numberRow(c, "Raise or lower  \u00b7 negative sinks it", Prefs.SWIPE_Y, "px");
        numberRow(c, "Swipe distance", Prefs.SWIPE_DIST, "px");
        appPickerRow(c, "App to open", "Choose the wallet card's app", Prefs.SWIPE_PKG);
        note(c, "The card uses the proven Accessibility overlay at the navigation-bar edge. Root leaves "
                + "HyperOS's actual navigation layout untouched, avoiding the SystemUI flicker/reboots caused "
                + "by the experimental navbar resource replacement.");

        // ---------------------------------------------------------- corner
        c = card("Corner gesture");
        switchRow(c, "Corner gesture", "On every screen, including inside apps", Prefs.CORNER_ON);
        choiceRow(c, "Trigger", Prefs.CORNER_TRIGGER, new int[]{0, 1}, new String[]{"Long-press", "Double-tap"});
        choiceRow(c, "Corner", Prefs.CORNER_SIDE, new int[]{2, 0}, new String[]{"Bottom right", "Bottom left"});
        choiceRow(c, "Action", Prefs.CORNER_ACTION, new int[]{0, 1}, new String[]{"Google", "Open an app"});
        appPickerRow(c, "App to open", "Used when the action is Open an app", Prefs.CORNER_PKG);
        numberRow(c, "Long-press hold time", Prefs.CORNER_MS, "ms");
        numberRow(c, "Zone width  · 0 = auto", Prefs.CORNER_W, "px");
        switchRow(c, "Show a dot where the zone is", null, Prefs.CORNER_HINT);
        switchRow(c, "Step aside for fullscreen video",
                "Hidden while the app on screen plays video in landscape", Prefs.CORNER_HIDE_VIDEO);
        note(c, "The corner stays on the proven Accessibility overlay even while root is active. The low-level "
                + "input observer is disabled on this firmware because it was part of the unstable navbar path.");

        // ---------------------------------------------------------- power button
        c = card("Power button gestures");
        powerChoiceRow(c, "Double-click", "Runs after the multi-press window closes",
                Prefs.POWER_DOUBLE_ACTION, RootPowerGestures.ACTION_IDS,
                RootPowerGestures.ACTION_NAMES);
        appPickerRow(c, "Double-click app", "Used when the action is Open an app",
                Prefs.POWER_DOUBLE_PKG);
        powerChoiceRow(c, "Triple-click", "Four or five clicks are ignored so emergency SOS remains stock",
                Prefs.POWER_TRIPLE_ACTION, RootPowerGestures.ACTION_IDS,
                RootPowerGestures.ACTION_NAMES);
        appPickerRow(c, "Triple-click app", "Used when the action is Open an app",
                Prefs.POWER_TRIPLE_PKG);
        numberRow(c, "Multi-press window", Prefs.POWER_MULTI_MS, "ms");
        note(c, "Requires TickerBar root. The helper only observes KEY_POWER; it never grabs or reinjects "
                + "the button. Single press, long press, the stock power menu and five-click emergency SOS "
                + "continue through HyperOS normally.");

        // ---------------------------------------------------------- google
        c = card("Google");
        choiceRow(c, "Opens to", Prefs.GOOGLE_MODE, new int[]{0, 1},
                new String[]{"Home feed", "Search box"});
        actionRow(c, "Open Google now", null, new Runnable() { public void run() {
            startActivity(new Intent(MainActivity.this, SearchActivity.class)); }});
        note(c, "Also available as a Quick Settings tile (“Google search”) and as a "
                + "TickerBar shortcut for Nova gestures.");

        // ---------------------------------------------------------- root
        c = card("Root access");
        rootLine = tv(RootBootstrap.status(this), 15, pal.onSurfaceVariant);
        rootLine.setPadding(dp(20), dp(18), dp(20), dp(14));
        rootLine.setLineSpacing(0, 1.15f);
        c.addView(rootLine);
        if (RootBootstrap.attemptsEnabled()) {
            if (RootBootstrap.autoEnabled()) {
                autoRootSwitchRow(c, "Automatically root after Shizuku starts",
                        "One guarded attempt per boot; disables itself if an attempt reboots the phone",
                        Prefs.AUTO_ROOT);
            } else {
                note(c, "Automatic root is safety-paused in this recovery build. Root starts only "
                        + "when you explicitly tap Start root attempt below.");
            }
            actionRow(c, "Start root attempt", "Requires Shizuku for the initial handoff",
                    new Runnable() { public void run() { startRootAttempt(); }});
            actionRow(c, "Apply root system integration",
                    "Combined system shade, stock volume, ticker, nav gestures and direct permissions",
                    new Runnable() { public void run() {
                        RootFeatures.applyAsync(MainActivity.this);
                        RootInput.stop();
                        RootOverlayClient.start(MainActivity.this);
                        toast("Applying root integration");
                    }});
            actionRow(c, "Prepare Shizuku automatic startup",
                    "Grants its boot permission and removes background restrictions",
                    new Runnable() { public void run() { prepareShizukuAutoStart(); }});
            actionRow(c, "Open Shizuku", "Pair once and start it in wireless-debugging mode",
                    new Runnable() { public void run() { openShizuku(); }});
            actionRow(c, "Open Wireless debugging", "Developer options → Wireless debugging",
                    new Runnable() { public void run() {
                        try { startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")); }
                        catch (Exception e) { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
                    }});
            note(c, "Shizuku setup and automatic startup\n\n"
                    + "1. Install Shizuku 13.6.0 or newer and allow HyperOS Autostart/background activity for it.\n\n"
                    + "2. In Developer options, enable USB debugging, USB debugging (Security settings), "
                    + "Wireless debugging and Disable adb authorization timeout.\n\n"
                    + "3. In Shizuku choose Start via wireless debugging, pair once, then start it once. "
                    + "Pairing is normally retained.\n\n"
                    + "4. Allow TickerBar when Shizuku asks. Tap Prepare Shizuku automatic startup once; "
                    + "this grants Shizuku WRITE_SECURE_SETTINGS, keeps ADB authorization permanent and "
                    + "allows background startup.\n\n"
                    + "5. On Android 13+ Shizuku 13.6 can start its ADB server after boot when the phone "
                    + "joins trusted Wi-Fi. TickerBar waits for its binder, then makes one guarded root attempt. "
                    + "If that attempt crashes/reboots the phone, automatic root switches itself off before "
                    + "the next attempt so it cannot form a boot loop. Root remains temporary and is lost at reboot.");
        } else {
            note(c, "Kernel root is safety-paused after a verified panic on this exact firmware. "
                    + "TickerBar will not arm it; Shizuku and Accessibility remain the stable path.");
        }
        actionRow(c, "Open floating screen recorder",
                "Starts HyperOS's on-screen recorder control, not its settings page",
                new Runnable() { public void run() {
                    RootFeatures.openRecorder(MainActivity.this, new RootFeatures.ResultCallback() {
                        public void done(final boolean ok, final String detail) {
                            runOnUiThread(new Runnable() { public void run() {
                                if (!ok) toast(detail.isEmpty() ? "Screen recorder failed" : detail);
                            }});
                        }
                    });
                }});
        actionRow(c, "Disable Xiaomi permission countdowns",
                "Accessibility, debugging, notification access and installs",
                new Runnable() { public void run() { setSecurityCountdowns(false); }});
        actionRow(c, "Restore Xiaomi permission countdowns",
                "Re-enables Security Center's stock warning activity",
                new Runnable() { public void run() { setSecurityCountdowns(true); }});
        switchRow(c, "Relax HyperOS memory killing",
                "Disables Smart Power Control's extra kill/free layer; Android's normal low-memory handling stays active",
                Prefs.MEMORY_RELAXED);
        note(c, "The floating recorder and replacement tile work through either root or Shizuku. "
                + "Countdown removal disables only Security Center's warning activity; the rest of Security "
                + "Center, Android permission dialogs and Play Protect remain intact.");

        // ---------------------------------------------------------- status bar
        c = card("Status bar");
        statusSlotRow(c, "Clock", "Show the stock HyperOS status-bar clock", "clock");
        statusIntRow(c, "Seconds", "Add seconds to the status-bar clock",
                "secure", "clock_seconds", 0);
        statusIntRow(c, "Notification icons", "Show app notification icons in the status bar",
                "system", "status_bar_show_notification_icon", 1);
        statusIntRow(c, "Network speed", "Live transfer speed beside the system icons",
                "system", "status_bar_show_network_speed", 0);
        statusIntRow(c, "Battery percentage", "Show the number in the battery area",
                "system", "status_bar_show_battery_percent", 1);
        statusChoiceRow(c, "Battery style", "system", "battery_indicator_style", 1,
                new int[]{1, 0, 2}, new String[]{"Number", "Graphic", "Top bar"});
        statusIntRow(c, "Carrier on lock screen", null,
                "system", "status_bar_show_carrier_under_keyguard", 1);
        statusSlotRow(c, "Wi-Fi icon", null, "wifi", "wimax");
        statusSlotRow(c, "Mobile signal", null,
                "phone_signal", "data_connection", "phone_evdo_signal", "cdma_eri");
        statusSlotRow(c, "Bluetooth", null,
                "bluetooth", "bluetooth_handsfree_battery", "ble_unlock_mode");
        statusSlotRow(c, "NFC", null, "nfc");
        statusSlotRow(c, "Location", null, "location", "gps");
        statusSlotRow(c, "Do Not Disturb", null, "zen", "quiet");
        statusSlotRow(c, "Silent / vibrate", null, "mute", "volume");
        statusSlotRow(c, "Alarm", null, "alarm_clock");
        statusSlotRow(c, "Headset", null, "headset", "mikey");
        statusSlotRow(c, "Casting", null, "cast");
        statusSlotRow(c, "Work profile", null, "managed_profile");
        statusSlotRow(c, "Keyboard", null, "ime");
        statusSlotRow(c, "Sync", null, "sync_active", "sync_failing");
        statusSlotRow(c, "VPN / secure connection", null, "secure");
        statusSlotRow(c, "Battery icon", null, "battery");
        note(c, "Changes use HyperOS's live SystemUI settings—no restart or resource overlay. "
                + "This build fixes the status-bar clock to the left; its position is not exposed because "
                + "moving the actual SystemUI view would require the same risky layout injection we removed.");

        // ---------------------------------------------------------- shade
        c = card("One shade");
        switchRow(c, "One shade like stock Android",
                "Pull down from the status bar for quick settings and notifications together", Prefs.ONE_SHADE);
        actionRow(c, "Preview UnifiedShade v2", "Opens the custom shade without changing the fallback",
                new Runnable() { public void run() {
                    if (!ShadeService.previewUnifiedShade())
                        toast("Turn on TickerBar Accessibility once to host the custom shade");
                }});
        switchRow(c, "Google Home shelf", "Scrollable light controls above notifications", Prefs.HOME_SHELF);
        switchRow(c, "Notifications expanded by default",
                "New cards start open; each card's arrow can still fold or expand it",
                Prefs.SHADE_NOTIFICATIONS_EXPANDED);
        toggleGuide(c);
        actionRow(c, "Allow brightness and auto-rotate", "Lets the shade's slider and rotate tile change them",
                new Runnable() { public void run() {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + getPackageName()))); }
                    catch (Exception e) { toast("Open Settings \u2192 Apps \u2192 TickerBar \u2192 Modify system settings"); } }});
        int[] accentIds = new int[UnifiedShade.ACCENTS.length];
        for (int i = 0; i < accentIds.length; i++) accentIds[i] = i;
        choiceRow(c, "Shade colours", Prefs.SHADE_THEME, new int[]{0, 1, 2},
                new String[]{"Match system theme", "Always dark", "Always light"});
        choiceRow(c, "Accent", Prefs.SHADE_ACCENT, accentIds, UnifiedShade.ACCENT_NAMES);
        colorRow(c, "Shade backdrop", "Tint behind the panels", Prefs.SHADE_BG_COLOR);
        colorRow(c, "Panel background", "Quick settings and Home cards", Prefs.SHADE_PANEL_COLOR);
        colorRow(c, "Inactive controls", "Tiles, buttons and notification cards", Prefs.SHADE_INACTIVE_COLOR);
        colorRow(c, "Active controls", "Enabled tiles, switches and highlights", Prefs.SHADE_ACTIVE_COLOR);
        colorRow(c, "Primary text", "Clock, titles and labels", Prefs.SHADE_TEXT_COLOR);
        colorRow(c, "Secondary text", "Dates, states and descriptions", Prefs.SHADE_SECONDARY_COLOR);
        colorRow(c, "Notification text", "Message bodies, independent of metadata", Prefs.SHADE_NOTIFICATION_TEXT_COLOR);
        actionRow(c, "Reset custom shade colours", "Return every colour to Material You",
                new Runnable() { public void run() {
                    String[] keys = {Prefs.SHADE_BG_COLOR, Prefs.SHADE_PANEL_COLOR,
                            Prefs.SHADE_INACTIVE_COLOR, Prefs.SHADE_ACTIVE_COLOR,
                            Prefs.SHADE_TEXT_COLOR, Prefs.SHADE_SECONDARY_COLOR,
                            Prefs.SHADE_NOTIFICATION_TEXT_COLOR};
                    for (String key : keys) Prefs.put(MainActivity.this, key, 0);
                    changed();
                    recreate();
                }});
        numberRow(c, "Panel opacity", Prefs.SHADE_ALPHA, "%");
        numberRow(c, "Blur behind  \u00b7 0 = off", Prefs.SHADE_BLUR, "dp");
        for (int i = 0; i < UnifiedShade.TILE_IDS.length; i++)
            choiceRow(c, UnifiedShade.TILE_NAMES[i], UnifiedShade.placeKey(UnifiedShade.TILE_IDS[i]),
                    new int[]{UnifiedShade.PLACE_BOTH, UnifiedShade.PLACE_GRID, UnifiedShade.PLACE_HIDDEN},
                    new String[]{"First row + expanded", "Expanded only", "Hidden"});
        note(c, "The first row shows up to six tiles. Wi-Fi, Bluetooth, mobile data, airplane mode and "
                + "location are switched by Android itself, so those tiles open its panel for them. "
                + "The Home shelf uses demo lights until Google Home OAuth is connected. "
                + "The lock screen and landscape keep the system shade.");

        c = card("Hidden apps");
        note(c, "Apps switched on here never appear on the ticker. With root they are also removed from "
                + "HyperOS's stock shade; the fallback one-shade hides them locally. Tip: long-press a "
                + "notification in the shade to hide just that type (like Messages' pairing notice) or the whole "
                + "app.");
        hiddenAppRows(c);
        hiddenTypeRows(c);

        c = card("Shade shortcuts");
        switchRow(c, "Only from the home screen", null, Prefs.SHADE_HOME);
        actionRow(c, "Open notifications", null, new Runnable() { public void run() {
            if (!ShadeService.notifications()) toast("Root or Accessibility fallback is not ready"); }});
        actionRow(c, "Open Control Centre", null, new Runnable() { public void run() {
            if (!ShadeService.quickSettings()) toast("Root or Accessibility fallback is not ready"); }});
        note(c, "Bind “TickerBar: Notifications” and “TickerBar: Control Centre” "
                + "in Nova → Gestures → Shortcuts.");

        // ---------------------------------------------------------- volume
        c = card("Volume buttons");
        switchRow(c, "Fine volume control", "Smaller media-volume steps with the physical buttons", Prefs.VOLUME_ON);
        choiceRow(c, "Levels per press", Prefs.VOLUME_STEP, new int[]{1, 2, 5, 10},
                new String[]{"1 · finest", "2", "5 · default", "10"});
        switchRow(c, "Ringer like stock Android",
                "When nothing's playing: ring \u2192 vibrate \u2192 silent on the volume buttons", Prefs.RINGER_CYCLE);
        numberRow(c, "Hold before repeating", Prefs.VOL_HOLD_MS, "ms");
        numberRow(c, "Repeat speed while held", Prefs.VOL_REPEAT_MS, "ms");
        numberRow(c, "Pause before vibrate / silent", Prefs.RINGER_PAUSE, "ms");
        actionRow(c, "Allow silent mode", "Do Not Disturb access, needed to switch to silent",
                new Runnable() { public void run() {
                    try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); }
                    catch (Exception e) { toast("Open Settings \u2192 Notifications \u2192 Do Not Disturb access"); } }});
        note(c, "Root leaves the physical keys, call volume, casting and HyperOS volume panel completely stock. "
                + "This phone's real media stream already exposes 150 levels. Fine-step and ringer-cycle settings "
                + "are retained for the Accessibility fallback only.");

        // ---------------------------------------------------------- updates
        c = card("Updates");
        switchRow(c, "Update automatically", "Checks when you open TickerBar", Prefs.AUTO_UPDATE);
        actionRow(c, "Check now", null, new Runnable() { public void run() {
            updateLine.setText("Checking…");
            Updater.checkAndInstall(MainActivity.this, new Updater.Cb() {
                public void msg(String m) { updateLine.setText(m); }
            });
        }});
        actionRow(c, "Allow restricted settings", "App info → ⋮ menu — needed on HyperOS",
                new Runnable() { public void run() {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()))); }});
        showSection(Math.max(0, Math.min(sections.size() - 1, Prefs.get(this).getInt(SECTION, 0))));
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        Updater.foreground(this);
        String l = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean nl = l != null && l.contains(getPackageName() + "/");
        String a = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean ac = a != null && a.contains(getPackageName() + "/");
        boolean rootUi = RootFeatures.applied() && NativeTicker.ready();
        chip(chipAcc, rootUi ? "Root UI on" : ac && ShadeService.isReady() ? "Accessibility fallback"
                        : ac ? "Accessibility: restart it" : "UI service off",
                rootUi || ac && ShadeService.isReady() ? 1 : ac ? 0 : -1);
        chip(chipNotif, nl ? "Notifications on" : "Notifications off", nl ? 1 : -1);
        ShadeService.refresh();
        RootBootstrap.start(this);
        refreshRootStatus();
        updateHandler.removeCallbacks(rootStatusUpdate);
        updateHandler.post(rootStatusUpdate);

        scheduleAutoUpdate();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) scheduleAutoUpdate();
        else updateHandler.removeCallbacks(autoUpdate);
    }

    private void scheduleAutoUpdate() {
        updateHandler.removeCallbacks(autoUpdate);
        if (resumed) updateHandler.postDelayed(autoUpdate, 600);
    }

    @Override protected void onPause() {
        resumed = false;
        Updater.background(this);
        updateHandler.removeCallbacks(autoUpdate);
        updateHandler.removeCallbacks(rootStatusUpdate);
        super.onPause();
    }

    private void refreshRootStatus() {
        if (rootLine == null) return;
        boolean ready = RootBootstrap.attemptsEnabled() && RootShell.available();
        String integration = ready ? "\n"
                + (RootFeatures.applied() ? "✓ combined SystemUI" : "… SystemUI setup") + "  ·  "
                + (NativeTicker.ready() ? "✓ native ticker" : "… ticker") + "  ·  "
                + "navbar stock  ·  "
                + "✓ stock volume" : "";
        rootLine.setText((ready ? "●  " : RootBootstrap.isBusy() ? "◐  " : "○  ")
                + RootBootstrap.status(this) + integration);
        rootLine.setTextColor(ready ? pal.good
                : RootBootstrap.isBusy() ? pal.warn : pal.onSurfaceVariant);
    }

    private void startRootAttempt() {
        if (!RootBootstrap.attemptsEnabled()) {
            RootBootstrap.request(this);
            refreshRootStatus();
            toast("Root attempts are paused after the kernel panic");
            return;
        }
        RootBootstrap.request(this);
        refreshRootStatus();
        if (RootShell.available()) {
            toast("Root daemon is ready; Shizuku is the fallback");
        } else if (!Shell.installed(this)) {
            try { startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + Shell.SHIZUKU_PKG))); }
            catch (Exception e) { toast("Install Shizuku from the Play Store"); }
        } else if (!Shell.running()) {
            Intent i = getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU_PKG);
            if (i != null) startActivity(i);
        } else if (!Shell.granted()) {
            Shell.requestPermission();
        } else {
            toast("Root attempt started; keep the phone awake");
        }
    }

    /** Toggle Xiaomi's shared 10-second intercept activity; Security Center stays installed. */
    private void setSecurityCountdowns(final boolean enabled) {
        if (!RootShell.available()) {
            toast("Root is needed for HyperOS's protected Security Center setting");
            return;
        }
        String component = "com.miui.securitycenter/"
                + "com.miui.permcenter.privacymanager.SpecialPermissionInterceptActivity";
        String command = "pm " + (enabled ? "enable" : "disable") + " --user 0 "
                + component + "; content call --uri content://com.miui.securitycenter.remoteprovider"
                + " --method callPreference --arg SET --extra type:i:1"
                + " --extra key:s:permcenter_install_intercept_enabled"
                + " --extra value:b:" + enabled;
        Shell.runResult(command, new Shell.ResultCallback() { public void done(Shell.Result result) {
            if (result.code == 0)
                toast(enabled ? "Xiaomi permission countdowns restored"
                        : "Xiaomi permission countdowns disabled");
            else toast("Security Center setting failed (" + result.code + ")");
        }});
    }

    // ================================================================= sections

    private void showSection(int idx) {
        for (int i = 0; i < sections.size(); i++)
            for (View v : sections.get(i)) v.setVisibility(i == idx ? View.VISIBLE : View.GONE);
        Prefs.get(this).edit().putInt(SECTION, idx).apply();
        sv.scrollTo(0, 0);
    }

    /** Slide-out list of sections, styled like the rest of the app. */
    private void openDrawer() {
        if (drawer != null) return;
        final android.widget.FrameLayout scrim = new android.widget.FrameLayout(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeDrawer(); } });
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int topInset = 0, bottomInset = 0;
        WindowInsets wi = page.getRootWindowInsets();
        if (wi != null && Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets b = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topInset = b.top; bottomInset = b.bottom;
        }
        panel.setPadding(dp(12), topInset + dp(20), dp(12), bottomInset + dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(pal.surface);
        bg.setCornerRadii(new float[]{0, 0, dp(28), dp(28), dp(28), dp(28), 0, 0});
        panel.setClickable(true);
        TextView t = tv("Settings", 22, pal.onSurface);
        t.setTypeface(MEDIUM);
        t.setPadding(dp(16), 0, 0, dp(16));
        panel.addView(t);
        int cur = Prefs.get(this).getInt(SECTION, 0);
        for (int i = 0; i < sectionNames.size(); i++) {
            final int idx = i;
            TextView item = tv(sectionNames.get(i), 16, i == cur ? pal.onPrimaryContainer : pal.onSurface);
            item.setTypeface(i == cur ? MEDIUM : Typeface.DEFAULT);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(20), 0, dp(16), 0);
            item.setBackground(ripple(i == cur ? round(pal.primaryContainer, dp(28)) : null, pal.onSurface));
            item.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                showSection(idx);
                closeDrawer();
            }});
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.bottomMargin = dp(2);
            panel.addView(item, lp);
        }
        ScrollView ps = new ScrollView(this);
        ps.setFillViewport(true);
        ps.setBackground(bg);   // the panel's surface reaches top to bottom
        ps.addView(panel);
        int w = Math.min(dp(320), Math.round(getResources().getDisplayMetrics().widthPixels * 0.8f));
        scrim.addView(ps, new android.widget.FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(scrim, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setAlpha(0f);
        ps.setTranslationX(-w);
        scrim.animate().alpha(1f).setDuration(180).start();
        ps.animate().translationX(0).setDuration(240)
                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        drawer = scrim;
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback cb = new android.window.OnBackInvokedCallback() {
                public void onBackInvoked() { closeDrawer(); }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            backCb = cb;
        }
    }

    private void closeDrawer() {
        final View d = drawer;
        if (d == null) return;
        drawer = null;
        if (Build.VERSION.SDK_INT >= 33 && backCb != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCb);
            backCb = null;
        }
        d.animate().alpha(0f).setDuration(160).withEndAction(new Runnable() { public void run() {
            page.removeView(d);
        }}).start();
    }

    @Override public void onBackPressed() {
        if (drawer != null) { closeDrawer(); return; }
        super.onBackPressed();
    }

    // ================================================================= header

    private void header() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(18), 0, dp(14));

        ImageView menu = new ImageView(this);
        menu.setImageResource(R.drawable.qs_menu);
        menu.setImageTintList(ColorStateList.valueOf(pal.onSurface));
        menu.setPadding(dp(12), dp(12), dp(12), dp(12));
        menu.setBackground(ripple(round(pal.surface, dp(24)), pal.onSurface));
        menu.setContentDescription("Settings sections");
        menu.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openDrawer(); } });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(48), dp(48));
        ml.setMarginEnd(dp(10));
        top.addView(menu, ml);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        top.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(14), 0, 0, 0);
        TextView title = tv("TickerBar", 26, pal.onSurface);
        title.setTypeface(MEDIUM);
        text.addView(title);
        updateLine = tv(getIntent().getBooleanExtra(EXTRA_UPDATED, false)
                ? "Updated to v" + versionName() : "v" + versionName(), 13, pal.onSurfaceVariant);
        text.addView(updateLine);
        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(top);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipAcc = chipView(new Runnable() { public void run() {
            if (RootFeatures.applied() && NativeTicker.ready())
                toast("Root ticker and native shade are active; navbar remains stock");
            else startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }});
        chipNotif = chipView(new Runnable() { public void run() {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }});
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cp.setMarginEnd(dp(8));
        chips.addView(chipAcc, cp);
        chips.addView(chipNotif, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        col.addView(chips);

        TextView preview = tv("Preview ticker", 16, pal.onPrimary);
        preview.setTypeface(MEDIUM);
        preview.setGravity(Gravity.CENTER);
        preview.setBackground(ripple(round(pal.primary, dp(28)), pal.onPrimary));
        preview.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Item sample = new Item("TickerBar", "Preview",
                    "A long sample line so you can watch it scroll, hop around the camera cut-out and clear the rounded corners",
                    getPackageName(), null);
            if (!RootOverlayClient.post(sample) && !ShadeService.preview())
                toast("Root overlay is starting; try again in a moment");
        }});
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        pp.topMargin = dp(16);
        col.addView(preview, pp);
    }

    private TextView chipView(final Runnable r) {
        TextView t = tv("", 13, pal.onSurface);
        t.setTypeface(MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { r.run(); } });
        return t;
    }

    /** state: 1 good, 0 needs attention, -1 missing */
    private void chip(TextView t, String label, int state) {
        int fg = state > 0 ? pal.good : state == 0 ? pal.warn : pal.bad;
        t.setText((state > 0 ? "✓  " : "!  ") + label);
        t.setTextColor(fg);
        t.setBackground(ripple(round(Palette.alpha(fg, 0x22), dp(20)), fg));
    }

    /** A live preview of the wallet card, above its settings. */
    private void cardPreview(LinearLayout card) {
        final CardArt art = new CardArt(this);
        final RectF r = new RectF();
        cardPreview = new View(this) {
            @Override protected void onDraw(Canvas cv) {
                float cw = Math.min(getWidth() * 0.62f, dp(260)), ch = cw * 0.63f;
                float left = (getWidth() - cw) / 2f, top = (getHeight() - ch) / 2f;
                r.set(left, top, left + cw, top + ch);
                float op = Math.max(5, Math.min(100, Prefs.n(MainActivity.this, Prefs.CARD_OPACITY))) / 100f;
                art.draw(cv, r, op, Prefs.n(MainActivity.this, Prefs.CARD_STYLE),
                        Prefs.str(MainActivity.this, Prefs.CARD_LABEL));
            }
        };
        card.addView(cardPreview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(196)));
    }

    /** A setting changed: apply it live. */
    private void changed() {
        ShadeService.refresh();
        RootOverlayClient.refresh(this);
        NativeTicker.refresh(this);
        RootInput.stop();
        TickerListener.filterActive();
        MemoryProfile.applyAsync(this);
        if (cardPreview != null) cardPreview.invalidate();
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU_PKG);
        if (i != null) {
            try { startActivity(i); return; } catch (Exception ignored) {}
        }
        try { startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + Shell.SHIZUKU_PKG))); }
        catch (Exception e) { toast("Install Shizuku 13.6.0 or newer"); }
    }

    private void prepareShizukuAutoStart() {
        if (!Shell.privileged()) {
            toast("Start Shizuku or TickerBar root first");
            openShizuku();
            return;
        }
        final String pkg = Shell.SHIZUKU_PKG;
        String command = "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS; "
                + "settings put global adb_allowed_connection_time 0; "
                + "appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow; "
                + "appops set " + pkg + " START_FOREGROUND allow; "
                + "am set-standby-bucket " + pkg + " active; "
                + "cmd deviceidle whitelist +" + pkg;
        Shell.runResult(command, new Shell.ResultCallback() { public void done(final Shell.Result result) {
            runOnUiThread(new Runnable() { public void run() {
                if (result.code == 0) toast("Shizuku automatic startup is prepared");
                else toast("Shizuku setup failed: " + result.output.trim());
            }});
        }});
    }

    // ================================================================= cards and rows

    private LinearLayout card(String label) {
        TextView h = tv(label, 20, pal.onSurface);
        h.setTypeface(MEDIUM);
        h.setPadding(dp(8), dp(24), 0, dp(12));
        col.addView(h);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(pal.surface, dp(24)));
        c.setClipToOutline(true);
        c.setPadding(0, dp(4), 0, dp(4));
        col.addView(c);
        sections.add(new View[]{h, c});
        sectionNames.add(label);
        return c;
    }

    /** A tappable row: title and optional subtitle on the left, `end` on the right. */
    private LinearLayout row(LinearLayout card, String title, String subtitle, View end) {
        if (card.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(Palette.alpha(pal.outline, 0x55));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dl.setMarginStart(dp(20));
            dl.setMarginEnd(dp(20));
            card.addView(div, dl);
        }
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(64));
        r.setPadding(dp(20), dp(10), dp(16), dp(10));
        r.setBackground(ripple(null, pal.onSurface));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = tv(title, 16, pal.onSurface);
        text.addView(t);
        if (subtitle != null) {
            TextView st = tv(subtitle, 13, pal.onSurfaceVariant);
            st.setPadding(0, dp(2), 0, 0);
            text.addView(st);
        }
        r.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (end != null) r.addView(end);
        card.addView(r);
        return r;
    }

    /**
     * What each tile can do and how to unlock the rest, with live status, so anyone setting
     * TickerBar up knows why some tiles open a settings page instead of switching.
     */
    private void toggleGuide(LinearLayout card) {
        boolean write = android.provider.Settings.System.canWrite(this);
        boolean secure = checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String status = "\u2022 Brightness & auto-rotate: " + (write ? "\u2713 working" : "needs \u201cAllow brightness and auto-rotate\u201d below")
                + "\n\u2022 Location: " + (secure ? "\u2713 switches directly" : "opens settings until you run the adb command below");
        boolean root = RootShell.available();
        note(card, "How the tiles work\n\n"
                + "Switch right away: Flashlight, Do Not Disturb, Sound, Auto-rotate, brightness and auto brightness.\n\n"
                + "Wi-Fi, Bluetooth, mobile data, airplane mode, location, NFC and battery saver are reserved for the system. With "
                + (root ? "TickerBar root ready, " : "root or Shizuku available, ")
                + "those tiles switch directly; otherwise they open Android's own panel. With TickerBar root, hotspot, "
                + "Ultra battery saver and reverse charging also switch directly; without root they open HyperOS settings.\n\n"
                + status + "\n\n"
                + "To make Location switch directly, connect the phone to a computer with adb (USB or wireless "
                + "debugging) and run once:\n"
                + "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS\n"
                + "It stays granted across reboots and updates.");
        actionRow(card, "Copy the adb command", null, new Runnable() { public void run() {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("adb",
                    "adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS"));
            toast("Copied");
        }});
    }

    /** Single notification types hidden from the shade's long-press menu; switch off to show again. */
    private void hiddenTypeRows(LinearLayout card) {
        java.util.LinkedHashMap<String, String[]> byId = new java.util.LinkedHashMap<String, String[]>();
        for (String[] type : Prefs.seenTypes(this)) byId.put(type[0] + "\t" + type[1], type);
        for (String[] type : Prefs.hiddenTypes(this)) byId.put(type[0] + "\t" + type[1], type);
        java.util.List<String[]> types = new java.util.ArrayList<String[]>(byId.values());
        if (types.isEmpty()) return;
        java.util.Collections.sort(types, new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                int app = a[0].compareToIgnoreCase(b[0]);
                return app != 0 ? app : a[2].compareToIgnoreCase(b[2]);
            }
        });
        note(card, "Notification types · switch on to hide");
        final android.content.pm.PackageManager pm = getPackageManager();
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        for (final String[] t : types) {
            String app = t[0];
            try { app = pm.getApplicationLabel(pm.getApplicationInfo(t[0], 0)).toString(); } catch (Exception ignored) {}
            final Switch sw = new Switch(this);
            sw.setChecked(Prefs.typeHidden(this, t[0], t[1]));
            sw.setThumbTintList(new ColorStateList(states, new int[]{pal.onPrimary, pal.outline}));
            sw.setTrackTintList(new ColorStateList(states, new int[]{pal.primary, pal.surfaceHigh}));
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    Prefs.setTypeHidden(MainActivity.this, t[0], t[1], t[2], on);
                    changed();
                }});
            LinearLayout r = row(card, t[2], app, sw);
            r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sw.toggle(); } });
        }
    }

    /** One switch per app that has notified (plus any already hidden), by name. */
    private void hiddenAppRows(LinearLayout card) {
        java.util.Set<String> pkgs = new java.util.TreeSet<String>(Prefs.set(this, Prefs.SEEN_APPS));
        pkgs.addAll(Prefs.set(this, Prefs.HIDDEN_APPS));
        android.service.notification.StatusBarNotification[] now = TickerListener.active();
        if (now != null) for (android.service.notification.StatusBarNotification n : now) pkgs.add(n.getPackageName());
        pkgs.remove(getPackageName());
        if (pkgs.isEmpty()) { note(card, "Apps show up here once they've sent a notification."); return; }
        final android.content.pm.PackageManager pm = getPackageManager();
        java.util.List<String[]> named = new java.util.ArrayList<String[]>();
        for (String p : pkgs) {
            String label = p;
            try { label = pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString(); } catch (Exception ignored) {}
            named.add(new String[]{label, p});
        }
        java.util.Collections.sort(named, new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return a[0].compareToIgnoreCase(b[0]); }
        });
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        for (final String[] app : named) {
            final Switch sw = new Switch(this);
            sw.setChecked(Prefs.hidden(this, app[1]));
            sw.setThumbTintList(new ColorStateList(states, new int[]{pal.onPrimary, pal.outline}));
            sw.setTrackTintList(new ColorStateList(states, new int[]{pal.primary, pal.surfaceHigh}));
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    Prefs.setHidden(MainActivity.this, app[1], on);
                    changed();
                }});
            LinearLayout r = row(card, app[0], app[0].equals(app[1]) ? null : app[1], sw);
            r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sw.toggle(); } });
        }
    }

    private void switchRow(LinearLayout card, String title, String subtitle, final String key) {
        final Switch sw = new Switch(this);
        sw.setChecked(Prefs.on(this, key));
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{pal.onPrimary, pal.outline}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{pal.primary, pal.surfaceHigh}));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.put(MainActivity.this, key, on ? 1 : 0);
                changed();
            }});
        LinearLayout r = row(card, title, subtitle, sw);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sw.toggle(); } });
    }

    /** The root bootstrap is re-evaluated only when its own switch changes. */
    private void autoRootSwitchRow(LinearLayout card, String title, String subtitle,
                                   final String key) {
        final Switch sw = styledSwitch(Prefs.on(this, key));
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                Prefs.put(MainActivity.this, key, on ? 1 : 0);
                RootBootstrap.settingsChanged(MainActivity.this);
            }});
        LinearLayout r = row(card, title, subtitle, sw);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sw.toggle(); } });
    }

    /** A SystemUI icon slot controlled by Android's native icon_blacklist tuner. */
    private void statusSlotRow(LinearLayout card, String title, String subtitle,
                               final String... slots) {
        final Switch sw = styledSwitch(SystemBarSettings.slotsVisible(this, slots));
        final boolean[] internal = new boolean[1];
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, final boolean on) {
                if (internal[0]) return;
                SystemBarSettings.setSlotsVisible(MainActivity.this, slots, on,
                        new SystemBarSettings.Callback() { public void done(boolean ok) {
                            if (ok) return;
                            internal[0] = true;
                            sw.setChecked(!on);
                            internal[0] = false;
                            toast("Root, Shizuku, or secure-settings access is required");
                        }});
            }});
        LinearLayout r = row(card, title, subtitle, sw);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sw.toggle(); } });
    }

    /** A boolean in HyperOS's own secure/system settings tables. */
    private void statusIntRow(LinearLayout card, String title, String subtitle,
                              final String table, final String key, int defaultValue) {
        final Switch sw = styledSwitch(SystemBarSettings.getInt(this, table, key, defaultValue) != 0);
        final boolean[] internal = new boolean[1];
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, final boolean on) {
                if (internal[0]) return;
                SystemBarSettings.putInt(MainActivity.this, table, key, on ? 1 : 0,
                        new SystemBarSettings.Callback() { public void done(boolean ok) {
                            if (ok) return;
                            internal[0] = true;
                            sw.setChecked(!on);
                            internal[0] = false;
                            toast("Root, Shizuku, or system-settings access is required");
                        }});
            }});
        LinearLayout r = row(card, title, subtitle, sw);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sw.toggle(); } });
    }

    private void statusChoiceRow(LinearLayout card, String title, final String table,
                                 final String key, int defaultValue,
                                 final int[] values, final String[] names) {
        final TextView val = tv(SystemBarSettings.nameFor(this, table, key, defaultValue,
                values, names), 14, pal.primary);
        val.setTypeface(MEDIUM);
        final LinearLayout r = row(card, title, null, val);
        r.addView(tv("  ▾", 14, pal.primary));
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            PopupMenu menu = new PopupMenu(MainActivity.this, val, Gravity.END);
            for (int i = 0; i < names.length; i++) menu.getMenu().add(Menu.NONE, i, i, names[i]);
            menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                public boolean onMenuItemClick(final android.view.MenuItem item) {
                    SystemBarSettings.putInt(MainActivity.this, table, key,
                            values[item.getItemId()], new SystemBarSettings.Callback() {
                                public void done(boolean ok) {
                                    if (ok) val.setText(names[item.getItemId()]);
                                    else toast("Root, Shizuku, or system-settings access is required");
                                }});
                    return true;
                }});
            menu.show();
        }});
    }

    private Switch styledSwitch(boolean checked) {
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{pal.onPrimary, pal.outline}));
        sw.setTrackTintList(new ColorStateList(states, new int[]{pal.primary, pal.surfaceHigh}));
        return sw;
    }

    /** A value on the right; tapping the row opens a menu of choices. */
    private void choiceRow(LinearLayout card, String title, final String key,
                           final int[] values, final String[] names) {
        choiceRow(card, title, null, key, values, names);
    }

    private void choiceRow(LinearLayout card, String title, String subtitle, final String key,
                           final int[] values, final String[] names) {
        final TextView val = tv(nameFor(key, values, names), 14, pal.primary);
        val.setTypeface(MEDIUM);
        val.setMaxWidth(dp(190));
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout r = row(card, title, subtitle, val);
        TextView chev = tv("  ▾", 14, pal.primary);
        r.addView(chev);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            PopupMenu m = new PopupMenu(MainActivity.this, val, Gravity.END);
            for (int i = 0; i < names.length; i++) m.getMenu().add(Menu.NONE, i, i, names[i]);
            m.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    Prefs.put(MainActivity.this, key, values[item.getItemId()]);
                    val.setText(names[item.getItemId()]);
                    changed();
                    return true;
                }});
            m.show();
        }});
    }

    private String nameFor(String key, int[] values, String[] names) {
        int cur = Prefs.n(this, key);
        for (int i = 0; i < values.length; i++) if (values[i] == cur) return names[i];
        return String.valueOf(cur);
    }

    /** Arbitrary RGB override with an explicit Material You reset. */
    private void colorRow(LinearLayout card, String title, String subtitle, final String key) {
        final TextView value = tv("", 13, pal.onSurface);
        value.setTypeface(MEDIUM);
        value.setGravity(Gravity.CENTER);
        value.setMinWidth(dp(92));
        value.setPadding(dp(10), dp(7), dp(10), dp(7));
        paintColorValue(value, Prefs.n(this, key));
        LinearLayout r = row(card, title, subtitle, value);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            showColorDialog(title, key, value);
        }});
    }

    private void paintColorValue(TextView value, int color) {
        if (color == 0) {
            value.setText("System");
            value.setTextColor(pal.onSurface);
            value.setBackground(round(pal.surfaceHigh, dp(16)));
            return;
        }
        color |= 0xFF000000;
        value.setText(String.format(java.util.Locale.US, "#%06X", color & 0xFFFFFF));
        value.setTextColor(Palette.luminance(color) > 0.48 ? 0xFF111111 : 0xFFFFFFFF);
        value.setBackground(round(color, dp(16)));
    }

    private void showColorDialog(String title, final String key, final TextView value) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(22);
        box.setPadding(pad, dp(8), pad, 0);
        TextView help = tv("Drag around the wheel, then tune brightness. Hex stays available for exact colours.",
                13, pal.onSurfaceVariant);
        box.addView(help);

        int current = Prefs.n(this, key);
        final ColorWheelView wheel = new ColorWheelView(current == 0 ? pal.primary : current);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(220), dp(220));
        wp.topMargin = dp(10);
        box.addView(wheel, wp);

        TextView brightnessLabel = tv("Brightness", 13, pal.onSurfaceVariant);
        brightnessLabel.setPadding(0, dp(6), 0, 0);
        box.addView(brightnessLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final android.widget.SeekBar brightness = new android.widget.SeekBar(this);
        brightness.setMax(100);
        brightness.setProgress(Math.round(wheel.value() * 100));
        brightness.setProgressTintList(ColorStateList.valueOf(pal.primary));
        brightness.setThumbTintList(ColorStateList.valueOf(pal.primary));
        box.addView(brightness, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));

        final TextView swatch = tv("", 14, pal.onSurface);
        swatch.setGravity(Gravity.CENTER);
        swatch.setTypeface(MEDIUM);
        box.addView(swatch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        final EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setHint("#RRGGBB");
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(6);
        box.addView(hex, hp);
        final boolean[] internal = new boolean[1];
        final Runnable sync = new Runnable() { public void run() {
            int color = wheel.color();
            String text = String.format(java.util.Locale.US, "#%06X", color & 0xFFFFFF);
            internal[0] = true;
            hex.setText(text);
            hex.setSelection(text.length());
            internal[0] = false;
            paintColorValue(swatch, color);
        }};
        wheel.setListener(new Runnable() { public void run() {
            brightness.setProgress(Math.round(wheel.value() * 100));
            sync.run();
        }});
        brightness.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(android.widget.SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                wheel.setValue(progress / 100f);
                sync.run();
            }
            public void onStartTrackingTouch(android.widget.SeekBar s) {}
            public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        hex.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable e) {
                if (internal[0]) return;
                String raw = e.toString().trim().replace("#", "");
                if (!raw.matches("[0-9A-Fa-f]{6}")) return;
                try {
                    int color = (int) (0xFF000000L | Long.parseLong(raw, 16));
                    wheel.setColor(color);
                    brightness.setProgress(Math.round(wheel.value() * 100));
                    paintColorValue(swatch, color);
                } catch (Exception ignored) {}
            }
        });
        sync.run();
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNeutralButton("Use system", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        Prefs.put(MainActivity.this, key, 0);
                        paintColorValue(value, 0);
                        changed();
                    }})
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        int color = wheel.color();
                        Prefs.put(MainActivity.this, key, color);
                        paintColorValue(value, color);
                        changed();
                    }})
                .show();
    }

    /** Lightweight HSV wheel: hue around the ring, saturation outward, value on the slider. */
    private final class ColorWheelView extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float[] hsv = new float[3];
        private Runnable listener;

        ColorWheelView(int color) {
            super(MainActivity.this);
            setColor(color);
            setContentDescription("Colour wheel");
        }

        void setListener(Runnable r) { listener = r; }
        int color() { return android.graphics.Color.HSVToColor(hsv); }
        float value() { return hsv[2]; }
        void setValue(float value) { hsv[2] = Math.max(0f, Math.min(1f, value)); invalidate(); }
        void setColor(int color) { android.graphics.Color.colorToHSV(color, hsv); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.max(1, Math.min(cx, cy) - dp(9));
            int[] hues = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                    0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setShader(new android.graphics.SweepGradient(cx, cy, hues, null));
            c.drawCircle(cx, cy, r, paint);
            paint.setShader(new android.graphics.RadialGradient(cx, cy, r, 0xFFFFFFFF, 0x00FFFFFF,
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, paint);
            paint.setShader(null);
            paint.setColor(Palette.alpha(0xFF000000, Math.round((1f - hsv[2]) * 255)));
            c.drawCircle(cx, cy, r, paint);

            double a = Math.toRadians(hsv[0]);
            float x = cx + (float) Math.cos(a) * hsv[1] * r;
            float y = cy + (float) Math.sin(a) * hsv[1] * r;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5));
            paint.setColor(0xAA000000);
            c.drawCircle(x, y, dp(9), paint);
            paint.setStrokeWidth(dp(2));
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, dp(9), paint);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (e.getActionMasked() != android.view.MotionEvent.ACTION_DOWN
                    && e.getActionMasked() != android.view.MotionEvent.ACTION_MOVE) return true;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float dx = e.getX() - cx, dy = e.getY() - cy;
            float r = Math.max(1, Math.min(cx, cy) - dp(9));
            hsv[0] = ((float) Math.toDegrees(Math.atan2(dy, dx)) + 360f) % 360f;
            hsv[1] = Math.max(0f, Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / r));
            invalidate();
            if (listener != null) listener.run();
            return true;
        }
    }

    /** Power-native work starts only after an explicit power-action choice. */
    private void powerChoiceRow(LinearLayout card, String title, String subtitle,
                                final String key, final int[] values, final String[] names) {
        final TextView val = tv(nameFor(key, values, names), 14, pal.primary);
        val.setTypeface(MEDIUM);
        val.setMaxWidth(dp(190));
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout r = row(card, title, subtitle, val);
        r.addView(tv("  ▾", 14, pal.primary));
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            PopupMenu menu = new PopupMenu(MainActivity.this, val, Gravity.END);
            for (int i = 0; i < names.length; i++)
                menu.getMenu().add(Menu.NONE, i, i, names[i]);
            menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                public boolean onMenuItemClick(android.view.MenuItem item) {
                    Prefs.put(MainActivity.this, key, values[item.getItemId()]);
                    val.setText(names[item.getItemId()]);
                    changed();
                    RootPowerGestures.sync(MainActivity.this);
                    return true;
                }});
            menu.show();
        }});
    }

    /** A compact number field on the right, applied as you type. */
    private void numberRow(LinearLayout card, String title, final String key, String unit) {
        LinearLayout end = new LinearLayout(this);
        end.setGravity(Gravity.CENTER_VERTICAL);
        final EditText e = new EditText(this);
        e.setText(String.valueOf(Prefs.n(this, key)));
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        e.setSingleLine(true);
        e.setTextColor(pal.onSurface);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        e.setBackground(round(pal.surfaceHigh, dp(12)));
        e.setPadding(dp(12), dp(8), dp(12), dp(8));
        e.setMinWidth(dp(84));
        e.setSelectAllOnFocus(true);
        end.addView(e);
        TextView u = tv(unit, 13, pal.onSurfaceVariant);
        u.setPadding(dp(8), 0, 0, 0);
        u.setMinWidth(dp(34));
        end.addView(u);
        row(card, title, null, end).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { e.requestFocus(); }});
        e.addTextChangedListener(new Watcher() { public void afterTextChanged(Editable s) {
            try {
                Prefs.put(MainActivity.this, key, Integer.parseInt(s.toString().trim()));
                changed();
            } catch (NumberFormatException ignored) {
                // mid-edit (empty or a lone '-'); keep the last good value
            }
        }});
    }

    /** Free text under the title (package names). */
    private void textRow(LinearLayout card, String title, String hint, final String key) {
        LinearLayout r = row(card, title, null, null);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setGravity(Gravity.START);
        // the title block was weighted for a horizontal row; stacked, it just wraps
        r.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText e = new EditText(this);
        e.setText(Prefs.str(this, key));
        e.setHint(hint);
        e.setHintTextColor(pal.onSurfaceVariant);
        e.setTextColor(pal.onSurface);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setSingleLine(true);
        e.setBackground(round(pal.surfaceHigh, dp(12)));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        r.addView(e, lp);
        e.addTextChangedListener(new Watcher() { public void afterTextChanged(Editable s) {
            Prefs.put(MainActivity.this, key, s.toString().trim());
            changed();
        }});
    }

    /** A real launcher-app picker; preferences still store the stable package name. */
    private void appPickerRow(LinearLayout card, String title, String subtitle, final String key) {
        final TextView val = tv(appLabel(Prefs.str(this, key)), 14, pal.primary);
        val.setTypeface(MEDIUM);
        val.setMaxWidth(dp(180));
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout r = row(card, title, subtitle, val);
        r.addView(tv("  ›", 18, pal.primary));
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            final java.util.List<String[]> apps = launcherApps();
            if (apps.isEmpty()) { toast("No launchable apps found"); return; }
            String[] labels = new String[apps.size()];
            for (int i = 0; i < apps.size(); i++) {
                String[] app = apps.get(i);
                labels[i] = app[0] + (app[0].equals(app[1]) ? "" : "\n" + app[1]);
            }
            new android.app.AlertDialog.Builder(MainActivity.this)
                    .setTitle("Choose an app")
                    .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                        public void onClick(android.content.DialogInterface dialog, int which) {
                            String[] app = apps.get(which);
                            Prefs.put(MainActivity.this, key, app[1]);
                            val.setText(app[0]);
                            changed();
                        }})
                    .setNegativeButton("Cancel", null)
                    .show();
        }});
    }

    private java.util.List<String[]> launcherApps() {
        android.content.pm.PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> resolved =
                pm.queryIntentActivities(query, android.content.pm.PackageManager.MATCH_ALL);
        java.util.LinkedHashMap<String, String[]> unique = new java.util.LinkedHashMap<String, String[]>();
        for (android.content.pm.ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.packageName == null) continue;
            String pkg = info.activityInfo.packageName;
            CharSequence loaded = info.loadLabel(pm);
            String label = loaded == null || loaded.length() == 0 ? pkg : loaded.toString();
            if (!unique.containsKey(pkg)) unique.put(pkg, new String[]{label, pkg});
        }
        java.util.List<String[]> apps = new java.util.ArrayList<String[]>(unique.values());
        java.util.Collections.sort(apps, new java.util.Comparator<String[]>() {
            public int compare(String[] a, String[] b) {
                int byLabel = a[0].compareToIgnoreCase(b[0]);
                return byLabel != 0 ? byLabel : a[1].compareToIgnoreCase(b[1]);
            }});
        return apps;
    }

    private String appLabel(String pkg) {
        if (pkg == null || pkg.trim().isEmpty()) return "Choose app";
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) { return pkg; }
    }

    private void actionRow(LinearLayout card, String title, String subtitle, final Runnable r) {
        TextView chev = tv("›", 22, pal.onSurfaceVariant);
        row(card, title, subtitle, chev).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }});
    }

    private void note(LinearLayout card, String s) {
        TextView t = tv(s, 13, pal.onSurfaceVariant);
        t.setPadding(dp(20), dp(8), dp(20), dp(14));
        t.setLineSpacing(0, 1.15f);
        card.addView(t);
    }

    // ================================================================= helpers

    private void edgeToEdge(View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(pal.dark ? 0 : light, light);
            }
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets b = in.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    v.setPadding(b.left, b.top, b.right, b.bottom);
                } else {
                    v.setPadding(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                            in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
                }
                return in;
            }
        });
    }

    private TextView tv(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private RippleDrawable ripple(android.graphics.drawable.Drawable content, int on) {
        GradientDrawable mask = round(0xFFFFFFFF, dp(24));
        return new RippleDrawable(ColorStateList.valueOf(Palette.alpha(on, 0x24)), content, mask);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "?"; }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private abstract static class Watcher implements TextWatcher {
        public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }
}
