# TickerBar

A small status-bar utility for HyperOS / MIUI devices, built because Super Status Bar
and HyperOS between them wouldn't do these things.

## What it does

- **Notification ticker** drawn above the status bar as an Accessibility overlay,
  in **1, 2 or 3 line** modes. Text **hops around the camera cut-out**, fades softly
  wherever it's cut, and side padding follows the screen's real rounded-corner radius.
- **Works like a heads-up**: tap the ticker to open the notification (it's dismissed
  if the app asked for that), flick it up or sideways to dismiss, pull down to open the shade.
- **Two looks**: the classic black bar, or a **heads-up card** — a pill in the system's
  Material You colours (light or dark) with the app's icon, kept inside the status bar.
- **Animation options** — entry/exit style (3D flip, hinge, slide, fade) and duration,
  reveal or loop scrolling, speed, and the pause before scrolling.
- **Wallet card** peeking up from the bottom edge, Samsung Pay style, on the home and
  lock screens only. Swipe it up to open Google Wallet's default card. Pick a finish
  (white, black metal, platinum, gold, rose gold, midnight, emerald or system colours)
  and an optional label — Google Wallet doesn't share card art with other apps.
- **Search gesture** uses a configurable bottom-corner long-press or double-tap and
  passes ordinary navigation touches through to the button underneath.
- **Shortcuts** to open the notification shade and Control Centre separately, which
  HyperOS exposes no intent for, plus a Google Quick Settings tile.
- **Fine volume buttons** adjust media volume by 1, 2, 5 or 10 native levels per press
  (default 5), using the Accessibility key filter. Holds repeat; calls and remote
  playback keep normal behavior.
- **Checks for updates** from this repo's GitHub releases when you open it, downloads
  automatically, then shows the system install confirmation. Waits until the app is
  visible and unlocked; avoids the silent-install path rejected by HyperOS.
- **POCO F9 Ultra root bootstrap is safety-paused.** The exact target produced a verified
  kernel panic at the task-tail redirect, so release builds do not arm the bundled module.
  Shizuku and Accessibility remain the stable control path.
- **Working Shizuku integration** restores TickerBar's fallback access after reboot,
  replaces HyperOS's broken recorder tile, grants the recorder's missing permissions and
  opens its real floating control. The protected Security Center install interceptor still
  requires root because its provider enforces a signature-only permission.

## Permissions

| Permission | Why |
|---|---|
| Accessibility | draws the ticker and wallet card, handles the corner gesture and fine volume keys |
| Modify audio settings | sets media volume to the selected level |
| Notification access | reading notifications for the ticker |

On MIUI you may need **App info → ⋮ → Allow restricted settings** before notification
access can be granted to a sideloaded app.

## Building

No Gradle. Needs a JDK and the Android SDK build-tools:

```bash
./build.sh
```

Outputs a signed `tickerbar.apk`.

`compile-stubs` contains declarations used only by `javac` for Android APIs supplied by
the phone's tethering APEX; those stubs are not packaged in the APK.

The native module currently targets only `songyuan` running
`OS3.0.309.0.WGNMIXM`. Its Apache-2.0-licensed source is vendored under
`native/ghostlock` and requires Android NDK r29.
