package com.jakes.tickerbar;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Top-corner Quick Settings action for Android's real global power dialog. */
public class PowerTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.setSubtitle("Menu");
        tile.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        // HyperOS's global-actions dialog is owned by WindowManager. Collapse first or the
        // shade remains above it. Transaction 119 is IWindowManager.showGlobalActions() on
        // this Android 16 framework and is invoked with root/Shizuku's shell identity.
        Shell.runResult("cmd statusbar collapse; service call window 119",
                new Shell.ResultCallback() { @Override public void done(Shell.Result result) {
                    if (result.code == 0) return;
                    Toast.makeText(PowerTileService.this,
                            "Root or Shizuku is not ready", Toast.LENGTH_SHORT).show();
                }});
    }
}
