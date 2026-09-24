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

    private Palette pal;
    private LinearLayout col;
    private TextView updateLine;
    private TextView chipAcc, chipNotif;
    private View cardPreview;
    private final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    // ================================================================= screen

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        Prefs.migrate(this);
        pal = Palette.of(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(pal.bg);
        sv.setClipToPadding(true);   // nothing scrolls under the status bar icons
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(12), dp(18), dp(40));
        sv.addView(col);
        setContentView(sv);
        edgeToEdge(sv);

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
        numberRow(c, "Lift from bottom edge", Prefs.SWIPE_Y, "px");
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
        c = card("Shade shortcuts");
        switchRow(c, "Only from the home screen", null, Prefs.SHADE_HOME);
        actionRow(c, "Open notifications", null, new Runnable() { public void run() {
            if (!ShadeService.notifications()) toast("Turn on TickerBar in Accessibility first"); }});
        actionRow(c, "Open Control Centre", null, new Runnable() { public void run() {
            if (!ShadeService.quickSettings()) toast("Turn on TickerBar in Accessibility first"); }});
        note(c, "Bind “TickerBar: Notifications” and “TickerBar: Control Centre” "
                + "in Nova → Gestures → Shortcuts.");

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
    }

    @Override protected void onResume() {
        super.onResume();
        String l = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean nl = l != null && l.contains(getPackageName() + "/");
        String a = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean ac = a != null && a.contains(getPackageName() + "/");
        chip(chipAcc, ac && ShadeService.isReady() ? "Accessibility on" : ac ? "Accessibility: restart it" : "Accessibility off",
                ac && ShadeService.isReady() ? 1 : ac ? 0 : -1);
        chip(chipNotif, nl ? "Notifications on" : "Notifications off", nl ? 1 : -1);
        ShadeService.refresh();

        if (Updater.autoCheck(this, new Updater.Cb() {
            public void msg(String m) { updateLine.setText(m); }
        })) updateLine.setText("Checking for updates…");
    }

    // ================================================================= header

    private void header() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(4), dp(18), 0, dp(14));

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
        TextView h = tv(label, 14, pal.primary);
        h.setTypeface(MEDIUM);
        h.setPadding(dp(8), dp(28), 0, dp(10));
        col.addView(h);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(pal.surface, dp(24)));
        c.setClipToOutline(true);
        c.setPadding(0, dp(4), 0, dp(4));
        col.addView(c);
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
