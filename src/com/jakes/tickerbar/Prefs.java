package com.jakes.tickerbar;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;

/** Every setting and its default lives here, so the UI and the services always agree. */
public final class Prefs {
    public static final String NAME = "tickerbar";

    // ticker
    public static final String TICKER_ON    = "ticker_on";
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

    // shade shortcuts
    public static final String SHADE_HOME   = "shade_home";

    private static final String SCHEMA = "schema";
    private static final HashMap<String, Integer> DEF = new HashMap<String, Integer>();
    static {
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

    /** One-off resets when a default changes meaning. */
    public static void migrate(Context c) {
        SharedPreferences p = get(c);
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
