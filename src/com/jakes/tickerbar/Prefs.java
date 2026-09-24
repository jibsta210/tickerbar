package com.jakes.tickerbar;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    public static final String NAME = "tickerbar";
    public static final String PAD   = "pad_px";     // left/right padding in px
    public static final String HEIGHT= "height_px";  // bar height in px
    public static final String LINES = "lines";      // 1 or 2
    public static final String TEXT  = "text_sp";    // text size in sp
    public static final String DWELL = "dwell_ms";   // how long each notification shows
    public static final String SWIPE_ON  = "swipe_on";    // bottom edge swipe enabled
    public static final String SWIPE_H   = "swipe_h";     // strip height px
    public static final String SWIPE_PKG = "swipe_pkg";   // package to launch

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
    public static int pad(Context c)    { return get(c).getInt(PAD, 120); }
    public static int height(Context c) { return get(c).getInt(HEIGHT, 144); }
    public static int lines(Context c)  { return get(c).getInt(LINES, 1); }
    public static int textSp(Context c) { return get(c).getInt(TEXT, 13); }
    public static int dwell(Context c)  { return get(c).getInt(DWELL, 6000); }
    public static boolean swipeOn(Context c) { return get(c).getInt(SWIPE_ON, 0) == 1; }
    public static int swipeH(Context c)      { return get(c).getInt(SWIPE_H, 24); }
    public static String swipePkg(Context c) {
        return get(c).getString(SWIPE_PKG, "com.google.android.apps.walletnfcrel");
    }
}
