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

    private Palette pal;
    private LinearLayout col;
    private TextView updateLine;
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
        textRow(c, "App to open", "Package name", Prefs.SWIPE_PKG);
        note(c, "Lives inside the nav bar, never inside apps. Taps on it pass through to your nav buttons. "
                + "Google Wallet doesn't share card art with other apps, so pick the closest look.");

        // ---------------------------------------------------------- corner
        c = card("Corner gesture");
        switchRow(c, "Corner gesture", "On every screen, including inside apps", Prefs.CORNER_ON);
        choiceRow(c, "Trigger", Prefs.CORNER_TRIGGER, new int[]{0, 1}, new String[]{"Long-press", "Double-tap"});
        choiceRow(c, "Corner", Prefs.CORNER_SIDE, new int[]{2, 0}, new String[]{"Bottom right", "Bottom left"});
        choiceRow(c, "Action", Prefs.CORNER_ACTION, new int[]{0, 1}, new String[]{"Google", "Open an app"});
        textRow(c, "App to open", "For “Open an app”", Prefs.CORNER_PKG);
        numberRow(c, "Long-press hold time", Prefs.CORNER_MS, "ms");
        numberRow(c, "Zone width  · 0 = auto", Prefs.CORNER_W, "px");
        switchRow(c, "Show a dot where the zone is", null, Prefs.CORNER_HINT);
        switchRow(c, "Step aside for fullscreen video",
                "Hidden while the app on screen plays video in landscape", Prefs.CORNER_HIDE_VIDEO);
        note(c, "Anything that isn't your gesture passes straight through to the nav button underneath. "
                + "In double-tap mode a single tap waits ~0.3 s to see if a second one follows.");

        // ---------------------------------------------------------- google
        c = card("Google");
        choiceRow(c, "Opens to", Prefs.GOOGLE_MODE, new int[]{0, 1},
                new String[]{"Home feed", "Search box"});
        actionRow(c, "Open Google now", null, new Runnable() { public void run() {
            startActivity(new Intent(MainActivity.this, SearchActivity.class)); }});
        note(c, "Also available as a Quick Settings tile (“Google search”) and as a "
                + "TickerBar shortcut for Nova gestures.");

        // ---------------------------------------------------------- shade
        c = card("One shade");
        switchRow(c, "One shade like stock Android",
                "Pull down from the status bar for quick settings and notifications together", Prefs.ONE_SHADE);
        actionRow(c, "Shizuku", Shell.status(this), new Runnable() { public void run() {
            if (!Shell.installed(MainActivity.this)) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Shell.SHIZUKU_PKG))); }
                catch (Exception e) { toast("Install Shizuku from the Play Store"); }
            } else if (!Shell.running()) {
                Intent i = getPackageManager().getLaunchIntentForPackage(Shell.SHIZUKU_PKG);
                if (i != null) startActivity(i);
            } else if (!Shell.granted()) {
                Shell.requestPermission();
            } else {
                toast("Shizuku is ready");
            }
        }});
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
        choiceRow(c, "Nav buttons in the shade", Prefs.SHADE_NAV_ORDER, new int[]{0, 1, 2},
                new String[]{"Match HyperOS", "Back \u00b7 Home \u00b7 Recents", "Recents \u00b7 Home \u00b7 Back"});
        numberRow(c, "Panel opacity", Prefs.SHADE_ALPHA, "%");
        numberRow(c, "Blur behind  \u00b7 0 = off", Prefs.SHADE_BLUR, "dp");
        for (int i = 0; i < UnifiedShade.TILE_IDS.length; i++)
            choiceRow(c, UnifiedShade.TILE_NAMES[i], UnifiedShade.placeKey(UnifiedShade.TILE_IDS[i]),
                    new int[]{UnifiedShade.PLACE_BOTH, UnifiedShade.PLACE_GRID, UnifiedShade.PLACE_HIDDEN},
                    new String[]{"First row + expanded", "Expanded only", "Hidden"});
        note(c, "The first row shows up to six tiles. Wi-Fi, Bluetooth, mobile data, airplane mode and "
                + "location are switched by Android itself, so those tiles open its panel for them. "
                + "The lock screen and landscape keep the system shade.");

        c = card("Hidden apps");
        note(c, "Apps switched on here never appear in the one shade or on the ticker. Tip: long-press a "
                + "notification in the shade to hide just that type (like Messages' pairing notice) or the whole "
                + "app. HyperOS's own shade and lock screen still show them.");
        hiddenAppRows(c);
        hiddenTypeRows(c);

        c = card("Shade shortcuts");
        switchRow(c, "Only from the home screen", null, Prefs.SHADE_HOME);
        actionRow(c, "Open notifications", null, new Runnable() { public void run() {
            if (!ShadeService.notifications()) toast("Turn on TickerBar in Accessibility first"); }});
        actionRow(c, "Open Control Centre", null, new Runnable() { public void run() {
            if (!ShadeService.quickSettings()) toast("Turn on TickerBar in Accessibility first"); }});
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
        note(c, "Hold a button to repeat. Uses your phone’s existing volume levels (150 on POCO F9 Ultra). "
                + "Call volume keeps its normal behavior. Turn this off to restore normal buttons.");

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
        chip(chipAcc, ac && ShadeService.isReady() ? "Accessibility on" : ac ? "Accessibility: restart it" : "Accessibility off",
                ac && ShadeService.isReady() ? 1 : ac ? 0 : -1);
        chip(chipNotif, nl ? "Notifications on" : "Notifications off", nl ? 1 : -1);
        ShadeService.refresh();

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
        super.onPause();
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
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }});
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
            if (!ShadeService.preview()) toast("Turn on TickerBar in Accessibility first");
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
        if (cardPreview != null) cardPreview.invalidate();
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
        note(card, "How the tiles work\n\n"
                + "Switch right away: Flashlight, Do Not Disturb, Sound, Auto-rotate, brightness and auto brightness.\n\n"
                + "Wi-Fi, Bluetooth, mobile data, airplane mode, NFC and battery saver are reserved for the system. With Shizuku "
                + "running (free on the Play Store; start it from its app after each reboot) and TickerBar allowed in it, "
                + "those tiles switch directly; otherwise they open Android's own panel. Hotspot always opens settings, "
                + "and ultra battery saver and reverse charging open HyperOS's battery screen.\n\n"
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
        java.util.List<String[]> types = Prefs.hiddenTypes(this);
        if (types.isEmpty()) return;
        note(card, "Hidden notification types");
        final android.content.pm.PackageManager pm = getPackageManager();
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        for (final String[] t : types) {
            String app = t[0];
            try { app = pm.getApplicationLabel(pm.getApplicationInfo(t[0], 0)).toString(); } catch (Exception ignored) {}
            final Switch sw = new Switch(this);
            sw.setChecked(true);
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

    /** A value on the right; tapping the row opens a menu of choices. */
    private void choiceRow(LinearLayout card, String title, final String key,
                           final int[] values, final String[] names) {
        final TextView val = tv(nameFor(key, values, names), 14, pal.primary);
        val.setTypeface(MEDIUM);
        val.setMaxWidth(dp(190));
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        final LinearLayout r = row(card, title, null, val);
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
