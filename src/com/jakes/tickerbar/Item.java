package com.jakes.tickerbar;

import android.graphics.drawable.Icon;

/** One notification on its way to the ticker. */
final class Item {
    final String app, title, body, pkg;
    final Icon icon;   // the notification's small icon: monochrome, tinted like the system does

    Item(String app, String title, String body, String pkg, Icon icon) {
        this.app = app == null ? "" : app;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.pkg = pkg;
        this.icon = icon;
    }
}
