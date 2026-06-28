# Floating Toolbar (Android, Kotlin)

A system-wide floating overlay toolbar — collapsible bubble + resizable
panel with live stats, quick toggles, and a customizable tools grid —
inspired by "Game Space" style assistant widgets.

## How to open this project

1. Open Android Studio → **File ▸ Open** → select the `FloatingToolbarApp` folder.
2. Let Gradle sync. If Android Studio offers to update the Gradle/AGP version
   or regenerate the Gradle wrapper, accept it — this project ships without
   the binary `gradle-wrapper.jar`, so Android Studio will create it on first
   sync.
3. Run on a physical device or emulator running **API 24+** (Android 7.0).
   Overlay windows behave inconsistently on some emulators — a real device
   is more reliable for testing.

## First run

1. Launch the app → tap **Grant overlay permission** → enable
   "Allow display over other apps" for this app in system settings → go back.
2. Tap **Start Floating Toolbar**. The panel appears on top of whatever
   screen you're on (home screen, other apps, etc.) at its last saved
   position and size.
3. You can now switch to any other app — the panel stays floating on top.
4. Optionally: check **Start automatically on boot** and/or tap
   **Exempt from battery optimization** on the main screen, so Android is
   less likely to kill the overlay in the background or after a reboot.

## Window controls

| Control | Where | What it does |
|---|---|---|
| Move | drag the header bar | repositions the panel anywhere on screen (saved across restarts) |
| Resize | drag the corner grip (bottom-right) | freely resizes the panel between a minimum size and the screen bounds (saved) |
| Minimize | header icon | collapses the panel into a small draggable bubble |
| Restore | tap the bubble | re-expands the bubble back into the full panel |
| Fullscreen / Maximize | header icon (swaps icon to show current state) | toggles the panel between its saved size and full-screen |
| Close | header icon | stops the foreground service and removes the overlay entirely |

The **bubble** additionally:
- **Snaps to the nearest screen edge** when you let go of a drag.
- **Fades to ~55% opacity** after 3 seconds of inactivity, and returns to
  full opacity the moment you touch it again.

## Customizing which apps appear in the panel

Tap **Customize Apps** on the main screen (or the **Customize** tile inside
the floating panel itself) to open the picker:

- **Search box** at the top filters the full app list by name.
- **"All apps" section** lists every launchable app on the device —
  including system apps like Settings, Phone, Camera, and Clock, not just
  user-installed ones — each with a checkbox (up to 9 selected).
- **"Your shortcuts" section** shows your picks with a drag handle (☰) to
  reorder them, and a × to remove one directly.

Every change is saved instantly and broadcast to the running overlay, so
the panel's tools grid updates live without restarting the service. This
works without the restricted `QUERY_ALL_PACKAGES` permission because the
manifest declares a `<queries>` entry scoped to `ACTION_MAIN` /
`CATEGORY_LAUNCHER` — the Play-Store-safe way to list launchable apps.

## Tools grid

Built-in tools, alongside your customized app shortcuts:

| Tool | Behavior |
|---|---|
| Calculator / Browser | launches the device's default app for that role |
| Clean cache | clears this app's own cache (see limitation below) |
| Flashlight | toggles the torch via `CameraManager.setTorchMode` (no CAMERA permission needed); auto-turns off if the overlay is closed while on |
| DND | toggles Do Not Disturb directly once notification-policy access is granted; opens that settings screen on first use |
| Screen rec. / Stop rec. | starts/stops recording with MediaProjection; shows a small floating timer + stop button while active |
| Customize | opens the app picker described above |

## What's real vs. simulated

- **RAM usage, battery %, battery temperature** — all real, via
  `ActivityManager.MemoryInfo` and `BatteryManager`.
- **Brightness/volume sliders** — real; brightness requires the
  "Modify system settings" permission, which the app will prompt for.
- **Flashlight, DND** — fully real and functional (see permissions below).
- **CPU usage** — best-effort only. Android 8+ blocks unprivileged apps from
  reading `/proc/stat` on most devices, and there's no public replacement
  API. It will show "N/A" on most modern phones — an OS-level restriction,
  not something any app (legitimate or not) can fully work around without
  root.
- **Clean / Boost** — can only clear *this app's own* cache and request
  garbage collection. No unprivileged app can clear other apps' cache or
  kill their background processes on modern Android — claims to the
  contrary from other apps are mostly cosmetic.
- **Screen recording** — fully functional via `MediaProjection` +
  `MediaRecorder`. The system shows a one-time consent dialog per recording
  session (required by Android, not optional).
- **Auto-start on boot** — only fires if the user opted in *and* overlay
  permission is already granted; otherwise it's a silent no-op.

## Project structure

```
FloatingToolbarApp/
├── app/src/main/java/com/example/floatingtoolbar/
│   ├── MainActivity.kt            permission gate, start/stop, customize, auto-start, battery exemption
│   ├── AppPickerActivity.kt       searchable, drag-to-reorder picker for app shortcuts
│   ├── PrefsHelper.kt             persists app shortcuts, overlay position/size, auto-start flag
│   ├── BootReceiver.kt            optional auto-start after reboot
│   ├── FloatingToolbarService.kt  the overlay itself (bubble/panel/drag/resize/grid/flashlight/DND)
│   ├── SystemStatsHelper.kt       RAM/CPU/battery%/temp/brightness readers
│   ├── ScreenCaptureActivity.kt   requests screen-record consent
│   └── ScreenRecordService.kt    records + shows the floating recording indicator
└── app/src/main/res/
    ├── layout/                    activity_main, app picker + its 3 row types, floating_bubble,
    │                              floating_panel, floating_recording_indicator
    ├── drawable/                  all icons (minimize/fullscreen/close/drag/remove/resize/flashlight/stop)
    │                              + panel/bubble/indicator backgrounds
    └── values/                    colors, strings, styles, themes
```

## Suggested next improvements

- Light/dark theme toggle to match the system theme.
- Let the user hide/reorder the *built-in* tools too (currently only the
  custom app shortcuts are reorderable).
- Multi-window "favorites profiles" (e.g. a different shortcut set for work
  vs. gaming).
- Replace the simple hand-drawn vector icons with a polished icon set.
- Handle screen rotation mid-recording (currently locks to the orientation
  recording started in).
