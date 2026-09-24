# TickerBar

A small status-bar utility for HyperOS / MIUI devices, built because Super Status Bar
and HyperOS between them wouldn't do these things.

## What it does

- **Notification ticker** as an overlay, with **configurable side padding** so text
  clears curved screen corners, and **1 or 2 line** modes so the second line can sit
  below a centred punch-hole camera.
- **Bottom-edge swipe** to launch an app (Google Wallet by default) — the Samsung
  wallet-swipe behaviour HyperOS lacks.
- **Launcher shortcuts** to open the notification shade and Control Centre separately,
  which HyperOS exposes no intent for. Bind them to Nova gestures.
- **Self-updating** from this repo's GitHub releases.

## Permissions

| Permission | Why |
|---|---|
| Draw over other apps | the overlay bar and edge strip |
| Notification access | reading notifications for the ticker |
| Accessibility | the only reliable way to open the shade on Android 14+ |

On MIUI you may need **App info → ⋮ → Allow restricted settings** before notification
access can be granted to a sideloaded app.

## Building

No Gradle. Needs a JDK and the Android SDK build-tools:

```bash
./build.sh
```

Outputs a signed `tickerbar.apk`.
