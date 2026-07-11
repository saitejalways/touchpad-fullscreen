# Touchpad Driver and Gesture Research (2026-03-06)

## Scope
This note captures primary-source guidance for implementing touchpad-like behavior in this Android accessibility touchpad app, focused on:
- tap and click behavior
- tap-and-hold drag behavior
- two-finger scroll and right-click behavior
- multi-touch state modeling

## Primary References
- libinput tapping (latest): https://wayland.freedesktop.org/libinput/doc/latest/tapping.html
- libinput scrolling (latest): https://wayland.freedesktop.org/libinput/doc/latest/scrolling.html
- Synaptics driver man page (Arch): https://man.archlinux.org/man/synaptics.4.en
- Linux kernel MT protocol: https://www.kernel.org/doc/html/latest/input/multi-touch-protocol.html
- Windows touch gestures: https://support.microsoft.com/en-us/windows/touch-gestures-for-windows-a9d28305-4818-a5df-4e2b-e5590f850741

## Key Findings

### 1) Tap and tap-drag behavior
- libinput defines tap-to-click as a short down/up sequence with implementation-defined timeout and movement limits.
- libinput tap-and-drag model: a tap followed by finger-down-and-hold starts a logical button hold; moving then drags.
- Optional drag-lock model exists (continue drag across brief lift/re-touch), but should be a separate mode due to accidental-drag risk.
- Synaptics exposes equivalent tuning knobs:
  - `MaxTapTime` (tap timeout)
  - `MaxDoubleTapTime` (second tap window)
  - `TapAndDragGesture` (enable/disable tap-drag)
  - `ClickTime` (synthetic click down-up duration)

### 2) Scroll behavior and inversion
- libinput two-finger scroll uses a built-in distance threshold to begin scrolling; once active, small movement deltas are passed through.
- Natural scrolling means content follows finger direction (downward finger motion moves content downward).
- Synaptics notes scroll speed is independent from pointer acceleration and controlled by scroll deltas (`VertScrollDelta`, `HorizScrollDelta`).
- Synaptics also documents inversion via sign flip (negative scroll delta values).

### 3) Multi-touch contact modeling
- Linux MT protocol (Type B) uses per-contact slots and `ABS_MT_TRACKING_ID` lifecycle:
  - non-negative tracking ID = active contact
  - `-1` = slot released
- The practical takeaway is to keep per-contact state and transition logic explicit (start/update/end), instead of deriving gesture state from only aggregate centroid values.

### 4) Cross-platform user expectations
- Current Windows guidance includes:
  - single tap to select
  - two-finger slide to scroll
  - two-finger tap for context menu/right-click
- Precision touchpad support caveat is explicit in Windows docs.

## Implementation Implications For This App
- Keep separate gesture states:
  - single-finger pointer mode
  - drag-armed mode (post-tap)
  - active drag (logical left-button down)
  - two-finger pre-scroll grace window
  - active scroll mode
- Keep independent multipliers:
  - pointer sensitivity
  - scroll sensitivity
- Preserve natural-scroll direction by default.
- Keep right-click separate from scroll activation (double two-finger tap or strict two-finger tap classifier).
- Add optional drag-lock later as a user setting once baseline behavior is stable.

## Suggested Tunables to Expose
- Tap timeout
- Double-tap timeout
- Tap movement slop
- Hold-to-drag trigger delay
- Two-finger scroll grace window
- Pointer sensitivity
- Scroll sensitivity
- Natural scroll toggle

## Notes
- These references were re-checked and were not present in existing project markdown files at the time of writing.
