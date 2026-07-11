# Daemon hold-drag lag and apparent daemon drop

## Summary

When the shell daemon runtime was active, normal cursor movement, clicks, and scroll worked, but touch-and-hold drag lagged heavily and sometimes appeared to disable the daemon.

## Symptoms

- Hold-drag became extremely slow once the left mouse button was held.
- During drag, daemon cursor ground truth often stopped updating.
- In some runs, the app concluded the daemon was dead and turned the daemon path off.
- Normal movement without held drag was much better.

## What was not broken

The following layers were tested and passed:

- `TouchpadEngine`
- action routing
- queued backend dispatch
- local backend socket transport
- daemon command delivery for move/button commands

That narrowed the problem to the daemon runtime path and its interaction with drag.

## Root cause

This was not one single bug. It was a combination of two runtime issues:

### 1. Hot-path logging was too expensive

Temporary debug logging had been added for every daemon `MOVE_REL` and `BUTTON` command and for every low-level HID write.

Affected files:

- `app/src/main/java/com/alex/touchpad/backend/TouchpadShellDaemonMain.kt`
- `app/src/main/java/com/alex/touchpad/backend/ShellHidDeviceWriter.kt`

During hold-drag, the move rate is high enough that this logging materially slowed the path.

### 2. Drag input still competed with daemon cursor polling

`AppContainer` was still using `shellDaemonMutex` around input send paths, while a background daemon cursor poll was also trying to query cursor ground truth repeatedly.

That meant:

- drag traffic and daemon lifecycle/query traffic still contended with each other
- cursor polling continued even while a mouse button was held
- during drag, those cursor queries were not useful anyway because cursor ground truth was effectively stale under held-button movement on this device

Affected file:

- `app/src/main/java/com/alex/touchpad/core/AppContainer.kt`

## Resolution

### Removed hot-path logging

Removed the temporary per-command/per-report logging from:

- `TouchpadShellDaemonMain.kt`
- `ShellHidDeviceWriter.kt`

### Stopped over-serializing runtime input

Changed `AppContainer` so daemon runtime input calls:

- move
- button
- click
- scroll

no longer run behind the daemon lifecycle/query mutex.

### Disabled daemon cursor polling while a mouse button is held

Added a simple daemon-side button-mask mirror in `AppContainer` and used it to skip background daemon cursor queries during held-button drag.

This was the practical fix because:

- drag needs low-latency writes
- cursor polling is not useful during the held-drag phase on this device
- polling could resume again once the button was released

## Result

After these changes:

- daemon hold-drag became responsive again
- daemon no longer appeared to drop during drag
- normal daemon movement/click/scroll behavior remained intact

## Key lesson

For the daemon path, cursor-ground-truth polling must be treated as lower priority than runtime HID writes. During held drag, polling should not compete with input delivery.
