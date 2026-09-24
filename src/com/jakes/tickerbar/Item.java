package com.jakes.tickerbar;

import android.app.PendingIntent;
import android.graphics.drawable.Icon;

/** One notification on its way to the ticker. */
final class Item {
    final String app, title, body, pkg;
    final Icon icon;   // the notification's small icon: monochrome, tinted like the system does
    final PendingIntent intent;   // what tapping it in the shade would do; may be null
    final String key;             // the notification's key, to dismiss it after opening
    final boolean autoCancel;     // the app asked for it to go away once opened

    Item(String app, String title, String body, String pkg, Icon icon) {
        this(app, title, body, pkg, icon, null, null, false);
    }

    Item(String app, String title, String body, String pkg, Icon icon,
         PendingIntent intent, String key, boolean autoCancel) {
        this.app = app == null ? "" : app;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.pkg = pkg;
        this.icon = icon;
        this.intent = intent;
        this.key = key;
        this.autoCancel = autoCancel;
    }
}
