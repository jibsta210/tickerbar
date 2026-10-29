package com.jakes.tickerbar;

import android.app.PendingIntent;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

/** One notification on its way to the ticker. */
final class Item {
    final String app, title, body, pkg;
    final Icon icon;        // sender/avatar or notification large icon: keep its original colours
    final Icon smallIcon;   // monochrome fallback: tint it like the system does
    final PendingIntent intent;   // what tapping it in the shade would do; may be null
    final String key;             // the notification's key, to dismiss it after opening
    final boolean autoCancel;     // the app asked for it to go away once opened

    Item(String app, String title, String body, String pkg, Icon smallIcon) {
        this(app, title, body, pkg, null, smallIcon, null, null, false);
    }

    Item(String app, String title, String body, String pkg, Icon icon, Icon smallIcon,
         PendingIntent intent, String key, boolean autoCancel) {
        this.app = app == null ? "" : app;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.pkg = pkg;
        this.icon = icon;
        this.smallIcon = smallIcon;
        this.intent = intent;
        this.key = key;
        this.autoCancel = autoCancel;
    }

    /** Best ticker artwork plus whether it is a monochrome glyph that should be accented. */
    TickerIcon loadTickerIcon(Context context) {
        Drawable drawable = load(context, icon);
        if (drawable != null) return new TickerIcon(drawable, false);
        if (pkg != null && !pkg.isEmpty()) try {
            drawable = context.getPackageManager().getApplicationIcon(pkg).mutate();
        } catch (Exception ignored) {}
        if (drawable != null) return new TickerIcon(drawable, false);
        drawable = load(context, smallIcon);
        return drawable == null ? null : new TickerIcon(drawable, true);
    }

    private static Drawable load(Context context, Icon icon) {
        if (icon == null) return null;
        try {
            Drawable drawable = icon.loadDrawable(context);
            return drawable == null ? null : drawable.mutate();
        } catch (Exception ignored) {
            return null;
        }
    }

    static final class TickerIcon {
        final Drawable drawable;
        final boolean tint;
        TickerIcon(Drawable drawable, boolean tint) {
            this.drawable = drawable;
            this.tint = tint;
        }
    }
}
