package com.jakes.tickerbar;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

/** Every setting and its default lives here, so the UI and the services always agree. */
public final class Prefs {
    public static final String NAME = "tickerbar";

    // ticker
    public static final String TICKER_ON    = "ticker_on";
    public static final String STYLE        = "ticker_style";  // 0 classic bar, 1 heads-up card
    public static final String LINES        = "lines";
    public static final String HEIGHT       = "height_px";     // 0 = auto
    public static final String HOP          = "hop";
    public static final String HOP_GAP      = "hop_gap";
    public static final String AUTO_PAD     = "auto_pad";
    public static final String PAD          = "pad_px";
    public static final String TEXT         = "text_sp";
    public static final String OPACITY      = "bg_opacity";
    public static final String DWELL        = "dwell_ms";
    public static final String QUEUE        = "queue";
    public static final String SKIP_SILENT  = "skip_silent";
    public static final String SKIP_ONGOING = "skip_ongoing";
    public static final String IGNORE       = "ignore_pkgs";

    // animation
    public static final String ANIM_IN      = "anim_in";       // 0 none, 1 fade, 2 slide down, 3 slide from right, 4 flip, 5 hinge
    public static final String ANIM_OUT     = "anim_out";      // 0 none, 1 fade, 2 slide up, 3 slide left, 4 flip, 5 hinge
    public static final String ANIM_MS      = "anim_ms";
    public static final String SCROLL_MODE  = "scroll_mode";   // TickerView.MODE_*
    public static final String SPEED        = "speed_pxs";
    public static final String DELAY        = "scroll_delay";

    // wallet button
    public static final String SWIPE_ON      = "swipe_on";
    public static final String SWIPE_HOME    = "swipe_home";
    public static final String SWIPE_PKG     = "swipe_pkg";
    public static final String SWIPE_TRIGGER = "swipe_trigger"; // 0 tap or swipe, 1 tap only, 2 swipe only
    public static final String SWIPE_POS     = "swipe_pos";     // 0 left, 1 centre, 2 right
    public static final String SWIPE_W       = "swipe_w_pct";
    public static final String SWIPE_H       = "swipe_h";       // 0 = auto (nav bar height)
    public static final String SWIPE_Y       = "swipe_y";
    public static final String SWIPE_DIST    = "swipe_dist";
    public static final String SWIPE_PILL    = "swipe_pill";    // draw the card
    public static final String CARD_HOME     = "card_home";     // show on the home screen
    public static final String CARD_LOCK     = "card_lock";     // show on the lock screen
    public static final String WALLET_QUICK  = "wallet_quick";  // open straight to the default card
    public static final String CARD_OPACITY  = "card_opacity";  // %
    public static final String CARD_PEEK     = "card_peek";     // px showing at rest; 0 = auto
    public static final String CARD_STYLE    = "card_style";    // CardArt.IDS
    public static final String CARD_LABEL    = "card_label";    // optional text on the card

    // corner long-press
    public static final String CORNER_ON     = "corner_on";
    public static final String CORNER_SIDE   = "corner_side";    // 0 left, 2 right
    public static final String CORNER_ACTION = "corner_action";  // 0 Google search, 1 open an app
    public static final String CORNER_PKG    = "corner_pkg";
    public static final String CORNER_MS     = "corner_ms";      // hold time
    public static final String CORNER_W      = "corner_w";       // px, 0 = auto
    public static final String CORNER_HINT   = "corner_hint";    // draw a dot where the zone is
    public static final String CORNER_TRIGGER= "corner_trigger"; // 0 long-press, 1 double-tap
    public static final String CORNER_HIDE_VIDEO = "corner_hide_video"; // step aside for fullscreen video
    public static final String HIDE_FRONT    = "hide_front_app"; // v1.9.6 switch, migrated to FRONT_MODE
    public static final String FRONT_MODE    = "front_mode";     // app on screen: 0 show all, 1 skip what it clears, 2 hide all

    // fine media volume buttons
    public static final String VOLUME_ON = "volume_buttons_on";
    public static final String VOLUME_STEP = "volume_button_step";
    public static final String HIDDEN_APPS  = "hidden_apps";    // packages kept out of the shade and the ticker
    public static final String SEEN_APPS    = "seen_apps";      // packages that have notified, for the hide list
    public static final String HIDDEN_TYPES = "hidden_types";   // single channels hidden: "pkg\tchannel\tname" per line
    public static final String SEEN_TYPES   = "seen_types";     // channels observed by the listener, same format
    public static final String ONE_SHADE    = "one_shade";
    public static final String SHADE_THEME  = "shade_theme";    // 0 match system, 1 dark, 2 light
    public static final String SHADE_ACCENT = "shade_accent";   // UnifiedShade.ACCENTS index, 0 system
    public static final String SHADE_ALPHA  = "shade_alpha";    // panel opacity %
    public static final String SHADE_BLUR   = "shade_blur";     // blur behind, dp (0 off)
    // 0 follows the Material You palette; any non-zero value is an opaque custom ARGB colour.
    public static final String SHADE_BG_COLOR = "shade_bg_color";
    public static final String SHADE_PANEL_COLOR = "shade_panel_color";
    public static final String SHADE_INACTIVE_COLOR = "shade_inactive_color";
    public static final String SHADE_ACTIVE_COLOR = "shade_active_color";
    public static final String SHADE_TEXT_COLOR = "shade_text_color";
    public static final String SHADE_SECONDARY_COLOR = "shade_secondary_color";
    public static final String SHADE_NOTIFICATION_TEXT_COLOR = "shade_notification_text_color";
    public static final String SHADE_NOTIFICATIONS_EXPANDED = "shade_notifications_expanded";
    public static final String HOME_SHELF   = "home_shelf";     // location-aware Home row in UnifiedShade
    public static final String HOME_WIDGET_ID = "home_widget_id"; // Google Home Favorites AppWidget id
    public static final String AUTO_BRIGHTNESS_LEVEL = "auto_brightness_level"; // 0..1000 native slider position
    public static final String SHADE_NAV_ORDER = "shade_nav_order"; // 0 follow HyperOS, 1 back-home-recents, 2 recents-home-back      // stock-Android single shade instead of HyperOS's split
    public static final String RINGER_CYCLE = "ringer_cycle";   // stock-Android ring/vibrate/silent on the buttons
    public static final String VOL_HOLD_MS   = "volume_hold_ms";   // hold before repeating starts
    public static final String VOL_REPEAT_MS = "volume_repeat_ms"; // between steps while held
    public static final String RINGER_PAUSE  = "ringer_pause_ms";  // a hold waits this long at vibrate / silent

    // updates
    public static final String AUTO_UPDATE   = "auto_update";
    public static final String LAST_CHECK    = "last_update_check";
    public static final String RELAUNCH_AT   = "relaunch_at";   // set just before a self-update

    // Google: 0 = the app's home feed (Discover cards), 1 = straight to the search box
    public static final String GOOGLE_MODE   = "google_mode";

    // Root profile: keep standard Android process management but disable HyperOS's
    // extra Smart Power Control/free-memory kill layer for this boot.
    public static final String MEMORY_RELAXED = "memory_relaxed";
    public static final String AUTO_ROOT = "auto_root_after_shizuku";

    // Passive root power-button gestures. Actions are RootPowerGestures.ACTION_*.
    public static final String POWER_DOUBLE_ACTION = "power_double_action";
    public static final String POWER_TRIPLE_ACTION = "power_triple_action";
    public static final String POWER_DOUBLE_PKG = "power_double_pkg";
    public static final String POWER_TRIPLE_PKG = "power_triple_pkg";
    public static final String POWER_MULTI_MS = "power_multi_ms";
    public static final String POWER_STOCK_DOUBLE = "power_stock_double";

    // shade shortcuts
    public static final String SHADE_HOME   = "shade_home";

    private static final String SCHEMA = "schema";
    private static final HashMap<String, Integer> DEF = new HashMap<String, Integer>();
    static {
        DEF.put(VOLUME_ON, 1); DEF.put(VOLUME_STEP, 5); DEF.put(RINGER_CYCLE, 0); DEF.put(ONE_SHADE, 0);
        DEF.put(SHADE_NAV_ORDER, 0); DEF.put(SHADE_THEME, 0); DEF.put(SHADE_ACCENT, 0); DEF.put(SHADE_ALPHA, 95); DEF.put(SHADE_BLUR, 36);
        DEF.put(SHADE_BG_COLOR, 0); DEF.put(SHADE_PANEL_COLOR, 0); DEF.put(SHADE_INACTIVE_COLOR, 0);
        DEF.put(SHADE_ACTIVE_COLOR, 0); DEF.put(SHADE_TEXT_COLOR, 0); DEF.put(SHADE_SECONDARY_COLOR, 0);
        DEF.put(SHADE_NOTIFICATION_TEXT_COLOR, 0);
        DEF.put(SHADE_NOTIFICATIONS_EXPANDED, 0);
        DEF.put(HOME_SHELF, 1);
        DEF.put(AUTO_BRIGHTNESS_LEVEL, 500);
        DEF.put(VOL_HOLD_MS, 400); DEF.put(VOL_REPEAT_MS, 90); DEF.put(RINGER_PAUSE, 600);
        DEF.put(TICKER_ON, 1);   DEF.put(LINES, 1);       DEF.put(HEIGHT, 0);
        DEF.put(HOP, 1);         DEF.put(HOP_GAP, 14);    DEF.put(AUTO_PAD, 1);
        DEF.put(PAD, 120);       DEF.put(TEXT, 13);       DEF.put(OPACITY, 100);
        DEF.put(DWELL, 4000);    DEF.put(QUEUE, 1);       DEF.put(SKIP_SILENT, 1);
        DEF.put(SKIP_ONGOING, 1);
        DEF.put(ANIM_IN, 4);     DEF.put(ANIM_OUT, 4);    DEF.put(ANIM_MS, 380);
        DEF.put(SCROLL_MODE, 0); DEF.put(SPEED, 160);     DEF.put(DELAY, 700);
        DEF.put(SWIPE_ON, 0);    DEF.put(SWIPE_HOME, 0);  DEF.put(SWIPE_TRIGGER, 2);
        DEF.put(SWIPE_POS, 1);   DEF.put(SWIPE_W, 55);    DEF.put(SWIPE_H, 0);
        DEF.put(SWIPE_Y, 0);     DEF.put(SWIPE_DIST, 40); DEF.put(SWIPE_PILL, 1);
        DEF.put(SHADE_HOME, 1);
        DEF.put(CARD_HOME, 1);   DEF.put(CARD_LOCK, 1);   DEF.put(WALLET_QUICK, 1);
        DEF.put(CARD_OPACITY, 85); DEF.put(CARD_PEEK, 0);  DEF.put(CARD_STYLE, 0);
        DEF.put(CORNER_ON, 1);   DEF.put(CORNER_SIDE, 2); DEF.put(CORNER_ACTION, 0);
        DEF.put(CORNER_MS, 450); DEF.put(CORNER_W, 0);    DEF.put(CORNER_HINT, 0);
        DEF.put(GOOGLE_MODE, 0);
        DEF.put(MEMORY_RELAXED, 1);
        DEF.put(AUTO_ROOT, 1);
        DEF.put(POWER_DOUBLE_ACTION, 0); DEF.put(POWER_TRIPLE_ACTION, 0);
        DEF.put(POWER_MULTI_MS, 450);
        DEF.put(STYLE, 0);       DEF.put(CORNER_TRIGGER, 0); DEF.put(AUTO_UPDATE, 1);
        DEF.put(CORNER_HIDE_VIDEO, 1); DEF.put(FRONT_MODE, 1);
    }

    static {
        // one-shade tiles: the first six sit in the first row, the rest only when expanded
        for (int i = 0; i < UnifiedShade.TILE_IDS.length; i++)
            DEF.put(UnifiedShade.placeKey(UnifiedShade.TILE_IDS[i]), UnifiedShade.defaultPlace(i));
    }

    private Prefs() {}

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static int n(Context c, String key) {
        Integer d = DEF.get(key);
        return get(c).getInt(key, d == null ? 0 : d);
    }

    public static boolean on(Context c, String key) { return n(c, key) == 1; }

    public static String str(Context c, String key) {
        String def = SWIPE_PKG.equals(key) ? "com.google.android.apps.walletnfcrel" : "";
        return get(c).getString(key, def);
    }

    public static void put(Context c, String key, int v)    { get(c).edit().putInt(key, v).apply(); }
    public static void put(Context c, String key, String v) { get(c).edit().putString(key, v).apply(); }

    /** A comma-separated package set stored under key. */
    public static java.util.Set<String> set(Context c, String key) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        for (String p : str(c, key).split(",")) if (!p.trim().isEmpty()) out.add(p.trim());
        return out;
    }

    public static void putSet(Context c, String key, java.util.Set<String> v) {
        put(c, key, android.text.TextUtils.join(",", v));
    }

    public static boolean hidden(Context c, String pkg) { return set(c, HIDDEN_APPS).contains(pkg); }

    /** Hidden notification types (one app's channel), each {package, channel id, channel name}. */
    public static java.util.List<String[]> hiddenTypes(Context c) {
        return typeList(c, HIDDEN_TYPES);
    }

    public static java.util.List<String[]> seenTypes(Context c) {
        return typeList(c, SEEN_TYPES);
    }

    private static java.util.List<String[]> typeList(Context c, String key) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        for (String line : str(c, key).split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length >= 2 && !f[0].isEmpty()) out.add(new String[]{f[0], f[1], f.length > 2 ? f[2] : f[1]});
        }
        return out;
    }

    public static void rememberType(Context c, String pkg, String channel, String name) {
        if (pkg == null || pkg.isEmpty() || channel == null || channel.isEmpty()) return;
        java.util.List<String[]> seen = seenTypes(c);
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String[] type : seen) {
            if (type[0].equals(pkg) && type[1].equals(channel)) {
                found = true;
                type[2] = name == null || name.isEmpty() ? type[2] : name;
            }
            out.append(type[0]).append('\t').append(type[1]).append('\t').append(type[2]).append('\n');
        }
        if (!found) out.append(pkg).append('\t').append(channel).append('\t')
                .append(name == null || name.isEmpty() ? channel : name.replace('\t', ' ').replace('\n', ' '))
                .append('\n');
        put(c, SEEN_TYPES, out.toString());
    }

    public static boolean typeHidden(Context c, String pkg, String channel) {
        if (pkg == null || channel == null) return false;
        for (String[] t : hiddenTypes(c)) if (t[0].equals(pkg) && t[1].equals(channel)) return true;
        return false;
    }

    public static void setTypeHidden(Context c, String pkg, String channel, String name, boolean hide) {
        StringBuilder b = new StringBuilder();
        for (String[] t : hiddenTypes(c)) {
            if (t[0].equals(pkg) && t[1].equals(channel)) continue;
            b.append(t[0]).append('\t').append(t[1]).append('\t').append(t[2]).append('\n');
        }
        if (hide) b.append(pkg).append('\t').append(channel).append('\t')
                .append(name == null ? channel : name.replace('\t', ' ').replace('\n', ' ')).append('\n');
        put(c, HIDDEN_TYPES, b.toString());
        TickerListener.filterActive();
    }

    public static void setHidden(Context c, String pkg, boolean hide) {
        java.util.Set<String> s = set(c, HIDDEN_APPS);
        if (hide) s.add(pkg); else s.remove(pkg);
        putSet(c, HIDDEN_APPS, s);
        TickerListener.filterActive();
    }

    /** One-off resets when a default changes meaning. */
    public static void migrate(Context c) {
        SharedPreferences p = get(c);
        // v1.9.7: "quiet for the app you're in" grew a smarter default; switched off stays off.
        // Keyed on the old setting itself rather than the schema, so it runs exactly once
        // whatever schema number an install has reached.
        if (p.contains(HIDE_FRONT)) {
            SharedPreferences.Editor f = p.edit();
            if (p.getInt(HIDE_FRONT, 1) == 0) f.putInt(FRONT_MODE, 0);
            f.remove(HIDE_FRONT).apply();
        }
        int v = p.getInt(SCHEMA, 1);
        if (v >= 4) return;
        SharedPreferences.Editor e = p.edit();
        // v1.3: the swipe strip became a nav-bar button, so its old geometry no longer fits
        if (v < 2) e.remove(SWIPE_H).remove(SWIPE_W).remove(SWIPE_POS).remove(SWIPE_Y).remove(SWIPE_DIST);
        // v1.4: the button is swipe-only by request, and the 3D flip is the default animation
        if (v < 3) e.remove(SWIPE_TRIGGER).remove(ANIM_IN).remove(ANIM_OUT).remove(ANIM_MS);
        // v1.6: the card became a centred, card-width white card (Samsung Pay style)
        e.remove(SWIPE_POS).remove(SWIPE_W);
        e.putInt(SCHEMA, 4).apply();
    }
}
