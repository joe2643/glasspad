# GlassPad — Architecture

This document explains how GlassPad is built, for contributors and the curious. For install/usage,
see [README.md](README.md).

GlassPad is a fork of [DeskPad](https://github.com/auxiliaryutils/deskpad-app) by auxiliaryutils
(MIT); the core design below is theirs, extended here.

## The core idea

GlassPad runs as a normal foreground app on the phone while driving a **different display** — the
external monitor's Desktop-mode session. It needs a way to move a cursor, tap, drag, scroll, and
edit text on that other display without adb, root, or Shizuku.

The answer is an **`AccessibilityService`**. Once the user enables it in Settings, it can:

- **dispatch touch gestures to a specific display** — `GestureDescription.Builder.setDisplayId()`
  (API 30+), the mechanism behind tap / drag / scroll;
- **draw an overlay** on that display — a `TYPE_ACCESSIBILITY_OVERLAY` window renders the cursor;
- **read and edit on-screen nodes** — `getWindowsOnAllDisplays()` plus `AccessibilityNodeInfo`
  actions handle the keyboard, finding the field you clicked and setting its text.

This permission persists across reboots and needs no per-boot setup — the property that ruled out
the earlier Shizuku prototype.

## Layout: a pure core with a thin Android shell

The code splits along a hard boundary so the interesting logic is testable without a device.

```
io.github.joe2643.glasspad
├── core/                     ← pure Kotlin. NO android.* imports. 89 JUnit tests.
│   ├── Gestures.kt           PadFrame, PadPointer, GestureAction, GestureConfig, GestureRecognizer
│   ├── PointerAccel.kt       speed-based pointer acceleration (libinput's touchpad curve)
│   ├── DesktopScale.kt       desktop-scale slider steps (dpi) and the workspace they leave
│   ├── WmShell.kt            SystemUI desktop-mode commands and their output
│   ├── ActivityDump.kt       finds the front app task on a display in `dumpsys activity`
│   ├── GestureRecognizer     touch-frame stream → semantic GestureActions
│   ├── InputPipeline.kt      GestureAction → InputSink calls, gated by the lock
│   ├── InputSink.kt          the interface the pipeline drives (implemented by the Android layer)
│   ├── CursorController.kt   cursor position/sensitivity per display, clamped to bounds
│   ├── LockController.kt     the freeze-all-input safety gate
│   ├── DisplayTargetResolver resolves which display is the external one
│   ├── KeyMapper.kt          character/modifier mapping helpers
│   └── Geometry.kt           Point, Bounds
│
└── (Android layer)           ← the only place android.* lives
    ├── GlassPadA11yService.kt  the hub: owns the pipeline, tracks the display, does the keyboard
    ├── AccessibilityInputSink actually dispatches gestures (tap/drag/scroll) to the display
    ├── CursorOverlay.kt       the overlay window on the external display
    ├── CursorView.kt          draws the cursor
    ├── TouchpadView.kt        captures phone touches → PadFrames; draws finger marks + the guide
    ├── MainActivity.kt        UI: header, trackpad card, keyboard / desktop-scale sheet, nav row, lock
    ├── Ui.kt                  the palette and drawable helpers (the UI is built in code)
    ├── DisplayDensity.kt      optional: sets the external display's density (adb-granted)
    ├── DesktopShell.kt        optional: fullscreen via ActivityManager dump → WMShell (adb-granted)
    └── GlassPadIme.kt          a bundled IME (not on the main path; see "Keyboard")
```

Everything in `core/` is framework-free, so `./gradlew testDebugUnitTest` exercises the gesture
recognition, cursor math, lock gating, and pipeline routing with plain JUnit — no emulator, no
Robolectric.

## Data flow

One finger stroke on the phone becomes an action on the desktop through a short pipeline:

```
TouchpadView          GestureRecognizer        InputPipeline           AccessibilityInputSink
(MotionEvent)   ─▶    (PadFrame stream)   ─▶   (GestureAction)   ─▶    (dispatchGesture to displayId)
                       recognizes tap/                gated by                cursor overlay +
                       drag/scroll/click              LockController          touch gestures
```

1. **`TouchpadView`** turns each `MotionEvent` into a framework-free `PadFrame`
   (phase, every pointer with its stable id and position, time).
2. **`GestureRecognizer`** consumes the frame stream and emits semantic `GestureAction`s:
   `Move`, `LeftClick`, `RightClick`, `Scroll`, `DragStart/DragMove/DragEnd`,
   `PinchStart/Pinch/PinchEnd`, `Navigate`. Its rules follow libinput's touchpad tap state machine: thresholds
   are physical millimetres (1.3 mm tap slop, 180 ms tap and tap-and-drag windows), motion is held
   back while a touch could still be a tap, a finger that was resting before another landed never
   counts toward a tap, and cancel never clicks. `Move`/`DragMove` deltas are already accelerated by `PointerAccel`.

   **Two fingers never move the pointer** — Chrome Remote Desktop's rule (`TouchInputHandler`
   only moves the cursor while exactly one pointer is down). Whenever a second finger lands, even
   on a pointer that's already moving or beside a finger that was resting, the pair is judged from
   where they were when they met: a change in their distance of 2.5 mm that exceeds how far they
   drifted together is a pinch (so one finger may stay still, and the hand may drift); moving
   together is a scroll, and so is one finger moving 4 mm on while the other lags or rests. For
   300 ms a scroll can still become a pinch. After a scroll or pinch the pointer stays locked until
   every finger has lifted (CRD's `mSuppressCursorMovement`), so lifting the fingers one at a time
   never nudges the cursor; a second finger put back down resumes the scroll. This is synthesised from ChromeOS gestures (`immediate_interpreter.cc`),
   libinput (`evdev-mt-touchpad-gestures.c`) and Chrome Remote Desktop (`SwipePinchDetector`).
   `Pinch.scale` is cumulative (distance ÷ distance when the fingers met).

   Three fingers landing together are a navigation gesture (`Navigate`), mirroring Android's own
   touchpad gestures: their mean motion past 6 mm decides it — sideways → back at once, up → home
   on lift or recents once it rests 300 ms, down → nothing (notifications) — and a three-finger
   tap is recents. From then until every finger lifts, nothing moves, scrolls or pinches. The sink
   hands `Navigate` to the service, which clicks the desktop taskbar's button (see "Back / home /
   recents").
3. **`InputPipeline`** translates each action into `InputSink` calls, advancing the
   `CursorController` and **gating everything through `LockController`** (locked ⇒ no-op).
4. **`AccessibilityInputSink`** (the sink) performs the real work: it repositions the
   `CursorOverlay` and dispatches touch gestures to the external `displayId`.

The keyboard is a parallel path: `MainActivity`'s on-screen keys call methods on
`GlassPadA11yService`, which edits the clicked desktop field directly (see below).

## Threading

Input runs on the **main thread**. Touch events and `DisplayManager` callbacks are delivered
there, and `AccessibilityService.dispatchGesture` expects to be called there, so `CursorController`
and the sink's gesture state are only ever touched from one thread — no locks. The exception is
**window queries** (`getWindowsOnAllDisplays`, node trees, dumps): the keyboard, nav row and
fullscreen run on one worker thread in `GlassPadA11yService`, which also owns the keyboard's text
buffer, because the system can take seconds to answer (see "Focus"). Gesture completion
callbacks (`GestureResultCallback`) also fire on the main thread, which is what lets the scroll and
drag state machines below stay lock-free.

## The load-bearing techniques

These are the non-obvious parts that make GlassPad work on a second display. Each was validated
against a logging test surface on a real secondary display.

### Keyboard: coordinate-based editing, no focus

While GlassPad is foreground on the phone, **nothing on the desktop is input-focused** —
`findFocus(FOCUS_INPUT)` returns null. So typing can't rely on focus. Instead:

- The field is located by the **coordinates of your last click**: `getWindowsOnAllDisplays()`
  (not `getWindows()`, which only sees the default display) → the deepest node whose
  `getBoundsInScreen()` contains the point and which `isEditable`.
- Text is written with `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`. Because `SET_TEXT` is
  asynchronous, re-reading the node every keystroke drops characters, so the service keeps a local
  `StringBuilder` mirror of the field's text, synced when you click the field.
- On-screen keys are `Button`s (they never take input focus), so clicking them doesn't steal the
  desktop field's selection. The keyboard layout mirrors Gboard (letters / `?123` / `=\<` pages).

### Typing with the phone's own keyboard

GlassPad's keys never take focus, but an IME (Gboard) only appears for a focused text box. So the
phone-keyboard panel is a plain `EditText` on the phone: whatever IME the user has does all the
composing there (Chinese candidates, glide, voice), and **Send** / **Insert** hand the finished text
to `GlassPadA11yService.insertText`, which writes it at the caret like a GlassPad keystroke (and
presses Enter for Send). The window is focusable while that panel is open.

The catch: with the phone focused, accessibility reports only the phone's windows, so the fresh
search for the clicked field can come back empty. `insertText` first uses the field found at the
last click (`AccessibilityNodeInfo.refresh()` says whether it's still reachable); if writing it
fails, it clicks the last-click point on the desktop so the desktop takes focus back, then retries a
few times. `adb logcat -s GlassPadKeys` shows which path was taken.

### Enter that actually submits

`ACTION_IME_ENTER` returns `true` even on an unfocused field while doing nothing — a false positive.
So Enter uses two paths (validated with an editor-action test field):

1. If a **Send/Search/Go button sits on the field's row** (chat, search), click it — reliable and
   needs no focus.
2. Otherwise (e.g. a browser address bar) **request `ACTION_FOCUS`, wait for the IME to bind, then
   fire the editor action.** Desktop mode's per-display focus lets the field focus and deliver its
   real Go/Search/Send action.

### Live drag via continued gestures

Dispatching a drag as a single stroke on release makes the dragged item lurch at the end. Instead
the sink uses a **continued gesture**: an initial `StrokeDescription(..., willContinue = true)`
presses down and stays down; each `continueStroke()` (which must start exactly where the previous
ended) moves toward the current finger position; exactly one segment is in flight at a time (the
next is dispatched from the previous one's completion callback); a held-still finger gets a
zero-drift hold segment to keep the touch alive; release dispatches a final `willContinue = false`
segment. The result is a touch that tracks the finger in real time.

### Pinch: two continued strokes, sized for the app

The sink pinches with the drag's technique on two strokes mirrored around the cursor, laid along
whichever axis has more room. Apps zoom with `ScaleGestureDetector`, which stops scaling once the
fingers are closer than `config_minScalingSpan` (27 mm, in the *display's* dpi — 324 px on VITURE
glasses that report 305 dpi). So the injected distance never drops below 30 mm, starts at the
geometric mean of that and the widest spread that fits (equal room to zoom in and out), and is
start × the phone's cumulative scale.

### Scroll without overlap

Firing a swipe per touch frame makes gestures overlap and race (inconsistent, unresponsive). The
sink instead **accumulates scroll deltas and keeps one swipe in flight**, draining whatever
accumulated when the swipe completes — smooth momentum, no overlap.

### The cursor

There is no OS mouse pointer on the desktop, so GlassPad draws its own. `CursorOverlay` attaches a
`TYPE_ACCESSIBILITY_OVERLAY` window to the external display (via `createDisplayContext`) and
`CursorView` paints the pointer. This needs no "draw over other apps" permission. `CursorController`
keeps the position and must **preserve it across display changes** (don't re-center on every
`onDisplayChanged`).

### Focus: only while GlassPad is in front

Android keeps one top-focused display (this Pixel has no per-display focus), and a touch on a
focusable window moves it there. So every touch on GlassPad used to un-focus the app on the desktop
(its caption greyed out, keys stopped reaching it). A `FLAG_NOT_FOCUSABLE` window avoids that —
WindowManager doesn't move focus to a display whose touched window can't receive keys.

But it must not be permanent. When the phone is the top-focused display (you just opened GlassPad)
and GlassPad, its top activity, can't take focus, the system has **no focused window at all**:
accessibility then won't compute windows, so every `getWindowsOnAllDisplays` waits out a 5 s timeout
per display, and key events to the phone ANR ("Application does not have a focused window").
Measured on a Pixel 9 Pro XL, Android 17: 10 003 ms with the flag, 7 ms without.

So `MainActivity.onTopResumedActivityChanged` makes the window focusable exactly while GlassPad is
the top resumed activity, and non-focusable once focus moves to the desktop (your first click
there). From then on trackpad touches leave focus on the desktop — verified: after a click on the
desktop, sliding on the phone keeps `mTopFocusedDisplayId` on the external display.

Related: accessibility only reports the windows of the **top-focused** display. The keyboard and the
◁ ○ □ row read the desktop's windows, so they work once the desktop has focus; the ⛶ button doesn't
use accessibility at all (see below). All window queries run on the service's worker thread, so a
slow answer can never freeze the UI.

### Back / home / recents on the desktop

`performGlobalAction(BACK/HOME/RECENTS)` acts on the phone's own display, never the external one.
So the nav row clicks the **desktop taskbar's own buttons** instead: it finds the node whose view id
ends in `:id/back`, `:id/home` or `:id/recent_apps` among the external display's `TYPE_SYSTEM`
windows (never app windows) and performs `ACTION_CLICK`. That needs `flagReportViewIds`. If the
launcher renames them, `adb logcat -s GlassPadNav` lists what the taskbar exposes. With no external
display, the row falls back to the global actions.

### Desktop scale (optional, adb-granted)

Some displays report a tiny physical size — VITURE glasses claim ~305 dpi, so Android picks 416 dpi
and a 1920×1200 panel lays out like a 738×462 dp phone, with an oversized taskbar. The desktop-scale
panel (the monitor icon) sets the display's density like `wm density <dpi> -d <id>`; WindowManager stores it per display, so it
survives reconnects.

Like fullscreen (below), it goes beyond the accessibility service: it needs `WRITE_SECURE_SETTINGS`, granted
once with `adb shell pm grant io.github.joe2643.glasspad android.permission.WRITE_SECURE_SETTINGS`. Without
the grant the panel shows that command and everything else works as before. `DisplayDensity` calls
`IWindowManager.setForcedDisplayDensityForUser` with the app's own user id, after exempting
`IWindowManager` from hidden-API checks in this process (the `hiddenapibypass` library). Its KDoc
records the simpler routes that fail on Android 17 and why.

### Fullscreen (optional, adb-granted)

A desktop window keeps the taskbar on screen even when maximized. A **fullscreen task** doesn't: the
taskbar stashes, and an app that asks for immersive mode hides the status bar too. SystemUI's
window-manager shell can move a task out of its desk into fullscreen and back
(`desktopmode moveTaskOutOfDesk <task>` / `moveTaskToDesk <task> <desk>`). That needs
`MANAGE_ACTIVITY_TASKS` (`signature|recents`, not grantable) — but SystemUI holds it, and its shell
commands arrive through ActivityManager's dump, which only requires `DUMP` and
`PACKAGE_USAGE_STATS`. Both are adb-grantable once.

`DesktopShell` calls `IBinder.dump` on the activity service: `activities` to find the desktop's front
app task and its windowing mode (`ActivityDump`), then the WMShell command (`WmShell`). The ⛶
button toggles: a fullscreen task goes back into the display's desk, a desk window goes fullscreen.
`adb logcat -s GlassPadShell` shows each step.

## Safety: the lock

`LockController` is a single boolean gate that `InputPipeline` checks before dispatching anything;
the keyboard methods check it too. When locked, no gesture or keystroke reaches the desktop, and
`MainActivity` shows a "locked" overlay. Unlock is a deliberate **press-and-hold** so an accidental
tap can't re-enable input.

## About `GlassPadIme`

The repo includes a small `InputMethodService`. It is **not** on the main typing path — typing goes
through the accessibility service as described above, so users don't enable a keyboard. The IME is
kept as a fallback/experiment for a future path (an IME bound to the desktop field could send real
key events); it is inert unless explicitly selected as the system keyboard.

## Permissions summary

- **AccessibilityService** — the only required permission; scoped to `canPerformGestures`,
  `canRetrieveWindowContent`, `flagRetrieveInteractiveWindows`, and `flagReportViewIds` (to find the
  taskbar's nav buttons), observing **no** accessibility events.
- **`WRITE_SECURE_SETTINGS`** — optional, adb-granted once, used only by the desktop-scale panel.
- **`DUMP`, `PACKAGE_USAGE_STATS`** — optional, adb-granted once, used only by the ⛶ button.
  These are broad: `DUMP` lets the app read any system service's dump. GlassPad only asks the
  activity service for its task list and SystemUI for the two desktop-mode commands, and has no
  internet permission to send anything anywhere — but grant them only if you want the button.
- **No** internet, foreground service, notifications, `SYSTEM_ALERT_WINDOW`, root, or Shizuku.
- The service and IME exports are gated by their system-only `BIND_*` signature permissions.
