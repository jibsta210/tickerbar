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
        String label, id;
        int icon;
        Class<?> target;
        if (alias.endsWith("ControlCentreShortcut")) {
            label = "Control Centre"; id = "pin_control_centre"; icon = R.drawable.ic_cc;
            target = OpenControlCentreActivity.class;
        } else if (alias.endsWith("SearchShortcut")) {
            label = "Google search"; id = "pin_google_search"; icon = R.drawable.ic_search;
            target = SearchActivity.class;
        } else {
            label = "Notifications"; id = "pin_notifications"; icon = R.drawable.ic_notif;
            target = OpenNotificationsActivity.class;
        }
        Intent launch = new Intent(Intent.ACTION_MAIN).setClass(this, target);

        Intent result = null;
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm != null) {
            // distinct IDs: pinned shortcuts may not reuse a manifest shortcut's ID
            ShortcutInfo info = new ShortcutInfo.Builder(this, id)
                    .setShortLabel(label)
                    .setLongLabel(label.startsWith("Google") ? label : "Open " + label)
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
