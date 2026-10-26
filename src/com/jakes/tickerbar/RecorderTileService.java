package com.jakes.tickerbar;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Working replacement for HyperOS's recorder tile, whose TileService bind is broken here. */
public class RecorderTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setSubtitle(Shell.privileged() ? "Ready" : "Root/Shizuku offline");
            tile.updateTile();
        }
    }

    @Override public void onClick() {
        super.onClick();
        RootFeatures.openRecorder(this, new RootFeatures.ResultCallback() {
            @Override public void done(final boolean ok, final String detail) {
                if (ok) return;
                getMainExecutor().execute(new Runnable() { @Override public void run() {
                    Toast.makeText(RecorderTileService.this,
                            detail.isEmpty() ? "Screen recorder failed" : detail,
                            Toast.LENGTH_LONG).show();
                }});
            }
        });
    }
}
