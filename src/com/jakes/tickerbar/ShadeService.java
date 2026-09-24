package com.jakes.tickerbar;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Provides the only reliable way to open the shade / Control Centre on Android 14+. */
public class ShadeService extends AccessibilityService {

    private static ShadeService self;

    @Override protected void onServiceConnected() { super.onServiceConnected(); self = this; }
    @Override public void onDestroy() { if (self == this) self = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() {}

    public static boolean isReady() { return self != null; }

    public static boolean notifications() {
        return self != null && self.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
    }
    public static boolean quickSettings() {
        return self != null && self.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
    }
}
