package com.jakes.tickerbar;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;

/**
 * Answers a launcher's CREATE_SHORTCUT request (what Nova's gesture picker lists under
 * Shortcuts). Two activity-aliases point here; the alias name picks which shortcut.
 */
public class ShortcutActivity extends Activity {

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        String alias = getIntent().getComponent() == null ? ""
                : getIntent().getComponent().getClassName();
        boolean cc = alias.endsWith("ControlCentreShortcut");
        String label = cc ? "Control Centre" : "Notifications";
        int icon = cc ? R.drawable.ic_cc : R.drawable.ic_notif;
        Intent launch = new Intent(Intent.ACTION_MAIN).setClass(this,
                cc ? OpenControlCentreActivity.class : OpenNotificationsActivity.class);

        Intent result = null;
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm != null) {
            // distinct IDs: pinned shortcuts may not reuse a manifest shortcut's ID
            ShortcutInfo info = new ShortcutInfo.Builder(this, cc ? "pin_control_centre" : "pin_notifications")
                    .setShortLabel(label)
                    .setLongLabel("Open " + label)
                    .setIcon(Icon.createWithResource(this, icon))
                    .setIntent(launch)
                    .build();
            try { result = sm.createShortcutResultIntent(info); } catch (Exception ignored) {}
        }
        if (result == null) result = new Intent();
        // legacy extras: still what many launchers read from a CREATE_SHORTCUT result
        result.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
        result.putExtra(Intent.EXTRA_SHORTCUT_NAME, label);
        result.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(this, icon));
        setResult(RESULT_OK, result);
        finish();
    }
}
