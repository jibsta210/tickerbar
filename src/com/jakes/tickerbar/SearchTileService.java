package com.jakes.tickerbar;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: Google search from anywhere, one pull-down away. */
public class SearchTileService extends TileService {

    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override public void onClick() {
        Intent i = SearchActivity.searchIntent(this);
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ only accepts a PendingIntent here for apps targeting 34+
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
