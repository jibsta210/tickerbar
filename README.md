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
- **POCO F9 Ultra root bootstrap** targets `songyuan` on
  `OS3.0.309.0.WGNMIXM`, starts an app-authenticated root worker and retains Shizuku as the
  reboot/failure fallback. One bounded attempt is allowed per boot.
- **Root-native integration** enables HyperOS's real single-page shade, renders the ticker
  without Accessibility, keeps the stable overlay fallback for wallet/corner gestures,
  keeps the stock 150-level volume path, hides selected notification apps/channels from the
  stock shade and can relax HyperOS's extra process-killing layer without disabling Android's
  normal low-memory handling.
- **Unified Shade** is an optional single-page replacement with animated compact-to-expanded
  controls, grouped and individually expandable notifications, inline actions/replies, a live
  adaptive-brightness slider, the real SystemUI navigation bar, and full colour-wheel
  control over its backdrop, panels, controls and text. Notification expansion can default
  open or closed. Its compact Home shelf hosts Google Home's authenticated Favorites widget
  directly, uses Google's own structure header without a duplicate TickerBar toolbar, and keeps
  Google's live device state/actions after a one-time home/device selection. Device tiles retain
  Google Home's own live state colours. Multiple widget instances can be associated with their
  home Wi-Fi (for example Home and Cottage), selected automatically as the network changes, or
  switched manually by tapping the structure name. Non-expandable notifications omit
  empty expand affordances. Home and media cards share the shade's configured palette and
  opening motion; the compact media player is roughly 40% shorter, and individual notifications
  inside an expanded app group can be swiped away without dismissing the entire group.
  The shade battery meter follows the same Number, Graphic or Top bar style selected for
  HyperOS's real status bar, so the two surfaces stay consistent.
- **Working Shizuku integration** restores TickerBar's fallback access after reboot,
  replaces HyperOS's broken recorder tile, grants the recorder's missing permissions and
  opens its real floating control. Pairing is remembered; Shizuku 13.6+ can restart its
  ADB server after boot on trusted Wi-Fi once `WRITE_SECURE_SETTINGS` and background startup
  are prepared from TickerBar's Root page.
- **Guarded automatic root handoff** can wait for Shizuku after boot and make one root
  attempt. A pending attempt marker disables automation after an unexpected reboot, so an
  unstable kernel route cannot become an automatic boot loop. The Root page includes the
  complete Shizuku 13.6+ wireless-debugging and autostart setup.
- **Root power-button gestures** optionally map double- and triple-click to Home Controls,
  Google search, Wallet, camera, recorder, flashlight, or an app. The helper observes input
  passively and leaves single press, long press, the power menu, and five-click SOS stock.

## Permissions

| Permission | Why |
|---|---|
| Accessibility | fallback ticker, wallet card, corner gesture and fine volume keys when root is unavailable |
| Modify audio settings | sets media volume to the selected level |
| Notification access | reading notifications for the ticker |
| Display over other apps | root-native ticker (granted by TickerBar's root integration) |
| Usage access | identifies the foreground/home app for native ticker and wallet behavior |
| Nearby Wi-Fi / location | reads the connected network name to select the matching Google Home shelf; no location history is collected |

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
