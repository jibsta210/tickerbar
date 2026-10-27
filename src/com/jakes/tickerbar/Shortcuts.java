package com.jakes.tickerbar;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import java.util.Arrays;

/** Keeps legacy Nova-pinned IDs live across APK replacements. */
final class Shortcuts {
    private Shortcuts() {}

    static void publish(Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        try {
            manager.setDynamicShortcuts(Arrays.asList(
                    shortcut(context, "pin_notifications", "Notifications",
                            R.drawable.ic_notif, OpenNotificationsActivity.class),
                    shortcut(context, "pin_control_centre", "Control Centre",
                            R.drawable.ic_cc, OpenControlCentreActivity.class),
                    shortcut(context, "pin_google_search", "Google search",
                            R.drawable.ic_search, SearchActivity.class)));
        } catch (Throwable ignored) {}
    }

    private static ShortcutInfo shortcut(Context context, String id, String label,
                                         int icon, Class<?> target) {
        Intent launch = new Intent(Intent.ACTION_MAIN).setClass(context, target);
        return new ShortcutInfo.Builder(context, id)
                .setShortLabel(label)
                .setLongLabel(label.startsWith("Google") ? label : "Open " + label)
                .setIcon(Icon.createWithResource(context, icon))
                .setIntent(launch)
                .build();
    }
}
