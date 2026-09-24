package com.jakes.tickerbar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Opens the Google app's text search box. Target of the tile and the shortcuts. */
public class SearchActivity extends Activity {

    static final String GOOGLE = "com.google.android.googlequicksearchbox";

    /** GLOBAL_SEARCH is the search box itself; ASSIST would open Gemini instead. */
    static Intent searchIntent(Context c) {
        Intent i = new Intent("android.search.action.GLOBAL_SEARCH").setPackage(GOOGLE);
        if (i.resolveActivity(c.getPackageManager()) == null) {
            Intent app = c.getPackageManager().getLaunchIntentForPackage(GOOGLE);
            i = app != null ? app : new Intent("android.search.action.GLOBAL_SEARCH");
        }
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
