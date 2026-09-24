# TickerBar

A small status-bar utility for HyperOS / MIUI devices, built because Super Status Bar
and HyperOS between them wouldn't do these things.

## What it does

- **Notification ticker** drawn *above* the status bar (as an accessibility overlay),
  in **1, 2 or 3 line** modes. Text **hops around the camera cut-out**, fades softly
  wherever it's cut, and side padding follows the screen's real rounded-corner radius.
- **Two looks**: the classic black bar, or a **heads-up card** — a pill in the system's
  Material You colours (light or dark) with the app's icon, kept inside the status bar.
- **Animation options** — entry/exit style (3D flip, hinge, slide, fade) and duration,
  reveal or loop scrolling, speed, and the pause before scrolling.
- **Wallet card** peeking up from the bottom edge, Samsung Pay style, on the home and
  lock screens only. Swipe it up to open Google Wallet's default card. Pick a finish
  (white, black metal, platinum, gold, rose gold, midnight, emerald or system colours)
  and an optional label — Google Wallet doesn't share card art with other apps.
- **Corner gesture**: long-press or double-tap the bottom corner of the nav bar, on any
  screen, to open Google (home feed or search box) or an app. Everything else passes
  through to the nav buttons underneath.
- **Shortcuts** to open the notification shade and Control Centre separately, which
  HyperOS exposes no intent for, plus a Google Quick Settings tile.
- **Updates itself** from this repo's GitHub releases whenever you open it.

## Permissions

| Permission | Why |
|---|---|
| Accessibility | draws the ticker, wallet card and corner zone above system UI, opens the shade |
| Notification access | reading notifications for the ticker |

On MIUI you may need **App info → ⋮ → Allow restricted settings** before notification
access can be granted to a sideloaded app.

## Building

No Gradle. Needs a JDK and the Android SDK build-tools:

```bash
./build.sh
```

Outputs a signed `tickerbar.apk`.
