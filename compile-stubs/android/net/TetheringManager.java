package android.net;

import java.util.concurrent.Executor;

/** Compile-only declarations for APIs supplied by the device's tethering APEX. */
public class TetheringManager {
    public static final int TETHERING_WIFI = 0;

    public interface StartTetheringCallback {
        default void onTetheringStarted() {}
        default void onTetheringFailed(int error) {}
    }

    public String[] getTetheredIfaces() { throw new RuntimeException("compile stub"); }
    public void startTethering(int type, Executor executor, StartTetheringCallback callback) {
        throw new RuntimeException("compile stub");
    }
    public void stopTethering(int type) { throw new RuntimeException("compile stub"); }
}
