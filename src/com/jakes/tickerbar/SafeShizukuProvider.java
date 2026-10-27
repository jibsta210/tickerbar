package com.jakes.tickerbar;

/**
 * Shizuku's stock provider probes Sui from ContentProvider.onCreate(). On this
 * HyperOS/Android 16 build that early probe can receive an invalid shared-memory
 * descriptor and abort Application creation. TickerBar uses Shizuku, not Sui, so
 * skip that optional probe while preserving the normal binder handoff.
 */
public final class SafeShizukuProvider extends rikka.shizuku.ShizukuProvider {
    @Override public boolean onCreate() {
        disableAutomaticSuiInitialization();
        return super.onCreate();
    }
}
