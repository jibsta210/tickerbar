# TickerBar

A small status-bar utility for HyperOS / MIUI devices, built because Super Status Bar
and HyperOS between them wouldn't do these things.

## What it does

- **Notification ticker** drawn *above* the status bar (as an accessibility overlay),
  in **1, 2 or 3 line** modes. Text **hops around the camera cut-out** and side padding
  follows the screen's real rounded-corner radius.
- **Animation options** — entry/exit style and duration, reveal or loop scrolling,
  speed, and the pause before scrolling.
- **Wallet button** on the navigation bar (left, centre or right): tap or swipe up to
  launch an app, Google Wallet by default.
- **Launcher shortcuts** to open the notification shade and Control Centre separately,
  which HyperOS exposes no intent for. Bind them to Nova gestures.
- **Self-updating** from this repo's GitHub releases.

## Permissions

| Permission | Why |
|---|---|
| Accessibility | draws the ticker and wallet button above system UI, opens the shade |
| Notification access | reading notifications for the ticker |

On MIUI you may need **App info → ⋮ → Allow restricted settings** before notification
access can be granted to a sideloaded app.

## Building

No Gradle. Needs a JDK and the Android SDK build-tools:

```bash
./build.sh
```

Outputs a signed `tickerbar.apk`.
