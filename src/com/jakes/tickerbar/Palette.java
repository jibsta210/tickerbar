package com.jakes.tickerbar;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/**
 * Colours from the system theme: Material You's dynamic palette on Android 12+, following
 * light/dark, with a fixed fallback on older versions. Shared by the heads-up ticker and
 * the settings screen so both match the phone.
 */
final class Palette {
    final boolean dark;
    final int bg, surface, surfaceHigh, onSurface, onSurfaceVariant, outline;
    final int primary, onPrimary, primaryContainer, onPrimaryContainer;
    final int good, bad, warn;

    private Palette(boolean dark, int bg, int surface, int surfaceHigh, int onSurface,
                    int onSurfaceVariant, int outline, int primary, int onPrimary,
                    int primaryContainer, int onPrimaryContainer) {
        this.dark = dark;
        this.bg = bg; this.surface = surface; this.surfaceHigh = surfaceHigh;
        this.onSurface = onSurface; this.onSurfaceVariant = onSurfaceVariant; this.outline = outline;
        this.primary = primary; this.onPrimary = onPrimary;
        this.primaryContainer = primaryContainer; this.onPrimaryContainer = onPrimaryContainer;
        this.good = dark ? 0xFF6FD99A : 0xFF1F8A4C;
        this.bad = dark ? 0xFFFFB4AB : 0xFFBA1A1A;
        this.warn = dark ? 0xFFF5C451 : 0xFF8A6100;
    }

    static boolean isDark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    static Palette of(Context c) {
        boolean dark = isDark(c);
        if (Build.VERSION.SDK_INT >= 31) {
            if (dark) return new Palette(true,
                    col(c, android.R.color.system_neutral1_900),
                    col(c, android.R.color.system_neutral2_800),
                    col(c, android.R.color.system_neutral2_700),
                    col(c, android.R.color.system_neutral1_50),
                    col(c, android.R.color.system_neutral2_200),
                    col(c, android.R.color.system_neutral2_600),
                    col(c, android.R.color.system_accent1_200),
                    col(c, android.R.color.system_accent1_800),
                    col(c, android.R.color.system_accent1_700),
                    col(c, android.R.color.system_accent1_100));
            return new Palette(false,
                    col(c, android.R.color.system_neutral1_10),
                    col(c, android.R.color.system_neutral2_50),
                    col(c, android.R.color.system_neutral2_100),
                    col(c, android.R.color.system_neutral1_900),
                    col(c, android.R.color.system_neutral2_700),
                    col(c, android.R.color.system_neutral2_200),
                    col(c, android.R.color.system_accent1_600),
                    col(c, android.R.color.system_accent1_0),
                    col(c, android.R.color.system_accent1_100),
                    col(c, android.R.color.system_accent1_900));
        }
        if (dark) return new Palette(true, 0xFF121216, 0xFF1E1E24, 0xFF2A2A31, 0xFFE6E6EC,
                0xFFA9A9B4, 0xFF46464F, 0xFFB4B6FF, 0xFF1F2280, 0xFF36398F, 0xFFDFE0FF);
        return new Palette(false, 0xFFFBF9FF, 0xFFF0EEF8, 0xFFE6E4EF, 0xFF1B1B21,
                0xFF46464F, 0xFFC7C5D0, 0xFF4C51C4, 0xFFFFFFFF, 0xFFE0E0FF, 0xFF03046D);
    }

    private static int col(Context c, int id) { return c.getColor(id); }

    /** Same colour at a given alpha (0-255). */
    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }
}
