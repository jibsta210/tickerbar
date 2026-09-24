package com.jakes.tickerbar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Opens the Google app: its home feed, or straight to the search box. Target of the tile and shortcuts. */
public class SearchActivity extends Activity {

    static final String GOOGLE = "com.google.android.googlequicksearchbox";

    /**
     * The app's own launcher entry opens its home feed (Discover cards: news, stocks,
     * weather). GLOBAL_SEARCH opens the search box instead. ASSIST would open Gemini.
     */
    static Intent searchIntent(Context c) {
        android.content.pm.PackageManager pm = c.getPackageManager();
        Intent i = null;
        if (Prefs.n(c, Prefs.GOOGLE_MODE) == 1) {
            Intent box = new Intent("android.search.action.GLOBAL_SEARCH").setPackage(GOOGLE);
            if (box.resolveActivity(pm) != null) i = box;
        }
        if (i == null) i = pm.getLaunchIntentForPackage(GOOGLE);
        if (i == null) i = new Intent("android.search.action.GLOBAL_SEARCH");   // some other search app
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return i;
    }

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        try { startActivity(searchIntent(this)); }
        catch (Exception e) { Toast.makeText(this, "Google search isn't available", Toast.LENGTH_LONG).show(); }
        finish();
        overridePendingTransition(0, 0);
    }
}
