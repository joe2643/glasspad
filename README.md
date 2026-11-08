# GlassPad

**Your phone as the trackpad and keyboard for Android Desktop mode — on AR glasses or a monitor.**

Plug your phone into AR glasses (VITURE, XREAL…) or a monitor, pick **Desktop mode**, and you get a
real desktop with no way to point at it unless you carry a mouse. GlassPad turns the phone's screen
into a laptop-style **trackpad** (with a cursor on the big screen), an on-screen **keyboard** that
types into the field you clicked, and the **back / home / recents** buttons — all from your hand.

No root, no Shizuku, no PC needed for the core app. A couple of optional extras need a one-time adb
command.

> GlassPad is a fork of [**DeskPad**](https://github.com/auxiliaryutils/deskpad-app) by
> auxiliaryutils, released under the MIT License. The accessibility-based design, cursor overlay and
> keyboard relay are theirs; GlassPad reworks the gestures and adds the features below.

## What GlassPad adds to DeskPad

- **Moving never clicks.** A tap is a tap; sliding to aim and lifting doesn't click — like Chrome
  Remote Desktop or a laptop touchpad. Pointer acceleration makes small moves precise and fast swipes
  cross the screen.
- **Pinch to zoom**, centred on the cursor, and **three-finger gestures** for back, home and recents.
- **The desktop app keeps focus** while you use the trackpad, so window menus (fullscreen, split,
  windowed) stay open and the app stays active.
- **Back / Home / Recents buttons** that press the *desktop's* taskbar, not the phone's.
- **Fullscreen button** — hides the taskbar for the app you're using. *(optional, adb once)*
- **Desktop scale slider** — shrinks the oversized UI that AR glasses get. *(optional, adb once)*
- A redesigned phone UI.

## Requirements

- An Android phone with **Desktop mode** on an external display — e.g. a recent Pixel. Android 11
  (API 30) or newer.
- A USB-C display connection: AR glasses, a USB-C monitor, or a USB-C hub with HDMI.

Tested on a Pixel 9 Pro XL (Android 17) with VITURE glasses. The three-finger gestures are new and
have only been tested in unit tests so far.

## Install

There's no Play Store build. Build the APK yourself (see [For developers](#for-developers)) and
install it — `adb install app-debug.apk`, or copy it to the phone and tap it.

GlassPad uses its own app id (`io.github.joe2643.glasspad`), so it installs alongside DeskPad
without replacing it.

## One-time setup

GlassPad needs one permission: an **Accessibility** service, which is what lets it move a cursor,
click and type on the external display.

1. Open GlassPad and tap **Open Accessibility settings** on the "Turn on GlassPad" card.
2. In the Accessibility list choose **GlassPad**, turn it **on**, and confirm.
3. Come back. The dot turns green once a display is connected.

It survives reboots — you only do this once.

> **"App was denied access" / "Restricted setting"?** Android 13+ blocks accessibility for apps
> installed from a file manager or browser. Open **Settings → Apps → GlassPad**, tap **⋮** (top
> right) → **Allow restricted settings**, then turn the service on again. Installing with
> `adb install` avoids this.

### Optional extras (one adb command each)

Two features need permissions only adb can grant. Run these once with the phone connected to a
computer (USB or Wireless debugging); everything else works without them.

**Fullscreen button:**

```bash
adb shell pm grant io.github.joe2643.glasspad android.permission.DUMP
adb shell pm grant io.github.joe2643.glasspad android.permission.PACKAGE_USAGE_STATS
```

**Desktop scale slider:**

```bash
adb shell pm grant io.github.joe2643.glasspad android.permission.WRITE_SECURE_SETTINGS
```

See [Privacy & permissions](#privacy--permissions) for what these allow.

## Using it

1. Connect the phone to your glasses or monitor and choose **Desktop**.
2. Open GlassPad. The header reads **Ready · 1920×1080** (your display's size).
3. **Click once on the desktop** (tap the trackpad). From then on the desktop app keeps focus, and
   the keyboard and the back / home / recents buttons work.

### Trackpad gestures

| On the phone | On the desktop |
|---|---|
| Slide one finger | Move the cursor |
| Tap | Left click |
| Two-finger tap | Right click |
| Two-finger slide | Scroll |
| Two fingers apart / together | Pinch to zoom, centred on the cursor (one finger may stay still) |
| Tap, then press again and slide | Drag (move a window, select text) — lift to drop |
| Three-finger swipe left or right | Back |
| Three-finger swipe up | Home |
| Three-finger swipe up and hold | Recent apps |
| Three-finger tap | Recent apps |

A three-finger swipe down is left alone, since Android uses it for notifications. The trackpad shows
a short guide until you first touch it.

### Header buttons

| Icon | What it does |
|---|---|
| Monitor | Desktop scale: drag the slider (or **Smaller** / **Bigger**) to fit more on the desktop; **Reset** returns to the display's default. Remembered per display. On VITURE glasses 240 dpi works well. |
| Keyboard | Shows the keyboard. |
| Padlock | Locks the trackpad: nothing reaches the desktop until you **hold** "Hold to unlock". Handy when you put the phone down. |

### Keyboard

**Click the text field on the desktop first**, then type — that's where the text goes. The layout is
Gboard-like: letters with Shift, a **?123** symbols page (and **=\<** beyond it), Backspace, Space
and **Enter**, plus **Copy / Paste / Cut / Select all** chips. Keys pop up a preview and give a
haptic tap.

**Enter really submits:** in a chat box it presses Send; in an address bar or search box it goes.

### Bottom row

| Icon | What it does |
|---|---|
| ◁ ○ □ | Back, Home, Recents — presses the desktop taskbar's own buttons. |
| ⛶ | Switches the desktop app to fullscreen (the taskbar hides; video and reader apps hide the status bar too). Tap again to put it back in a window. Needs the optional grant. |

## Troubleshooting

- **"Connect a display in Desktop mode"** — plug in the display and choose **Desktop**, not screen
  mirroring.
- **Back / home / recents or typing do nothing** — click once on the desktop first. Android only
  lets GlassPad see the desktop's windows while the desktop has focus.
- **Typing doesn't appear** — click the desktop text field with the trackpad first.
- **The "Turn on GlassPad" card is back** — the service was turned off (some battery savers do
  this). Tap **Open Accessibility settings** and turn GlassPad back on.
- **Fullscreen or desktop scale shows an adb command** — run the grant from
  [Optional extras](#optional-extras-one-adb-command-each).
- **Something specific misbehaves** — `adb logcat -s GlassPadGesture GlassPadNav GlassPadShell GlassPadA11y`
  shows what GlassPad decided and why.

## Privacy & permissions

- **No internet permission.** GlassPad cannot send anything anywhere.
- **Accessibility service** (required): dispatches taps and swipes to the external display, draws
  the cursor, reads and writes the text field you clicked, and finds the taskbar's back / home /
  recents buttons. It listens to no accessibility events.
- **`WRITE_SECURE_SETTINGS`** (optional): lets the slider change the external display's density.
- **`DUMP` + `PACKAGE_USAGE_STATS`** (optional): let the fullscreen button ask Android which app is
  in front and tell the desktop to make it fullscreen. **These are broad** — `DUMP` lets an app read
  any system service's diagnostic dump. GlassPad only reads the task list and runs the two
  desktop-mode commands, and can't send data off the phone, but grant them only if you want the
  button.
- The optional features call hidden Android APIs through
  [`hiddenapibypass`](https://github.com/LSPosed/AndroidHiddenApiBypass). They rely on Android
  internals, so an Android update can break them; GlassPad then shows the error instead of crashing.
- No root, no Shizuku, no background service, no notifications, no "display over other apps".

## For developers

A framework-free `core/` package (gestures, pointer acceleration, pipeline, cursor and dump parsing —
**86 JUnit tests**, no emulator needed) with a thin Android layer on top. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the design: the gesture recognizer, focus handling, how the
desktop's taskbar and fullscreen are driven, and the threading model.

```bash
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:testDebugUnitTest    # run the core unit tests
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # signed release (needs keystore.properties, see below)
```

**Signing:** release signing reads a gitignored `keystore.properties` at the repo root. Copy
`keystore.properties.example` and fill in your keystore; without it, debug builds and an unsigned
release still build.

**Tuning:** gesture thresholds are physical millimetres in `GestureConfig` (`core/Gestures.kt`);
pointer acceleration lives in `core/PointerAccel.kt`; pinch sizing and dispatch timing in
`AccessibilityInputSink`.

## Credits

- [DeskPad](https://github.com/auxiliaryutils/deskpad-app) by auxiliaryutils — the app GlassPad is
  built on.
- Gesture rules informed by [libinput](https://wayland.freedesktop.org/libinput/doc/latest/)'s tap
  handling and touchpad acceleration, ChromeOS's gesture library, and Chrome Remote Desktop's pinch
  detection.
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) by LSPosed.

## License

[MIT](LICENSE), as the original. The LICENSE keeps the DeskPad contributors' copyright alongside
GlassPad's.

*GlassPad 0.3 · minSdk 30 · targetSdk 35 · Kotlin.*
