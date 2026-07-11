# Quick Cursor: Click Slop and Sensitivity Investigation

## Scope
This note documents how Quick Cursor decides whether a touch is a click vs drag ("click slop"), and how movement sensitivity ("cursor speed") is applied.

Codebase analyzed:
- Decompiled Quick Cursor APK smali from a local analysis workspace

## 1) Click Slop (distance threshold)

### Where the threshold comes from
- Preference key: `clickDistanceThreshold`
  - Defined as repository key `K` in:
    - `smali/com/quickcursor/repositories/c.smali` lines `681-689`
- Loaded on tracker touch handler init into `Lf7/l;->v:I`:
  - `smali/f7/l.smali` lines `109-117`
  - note: value is read as float then cast to int (`float-to-int`), so runtime threshold is integer px
- Backed by tap settings UI entry:
  - `res/xml/preferences_tap_behaviour.xml` line `22`

### Default and range
- Default: `6.0dp`
- Min: `1.0px`
- Max: `30.0dp`
  - `res/values/dimens.xml` lines `61`, `231`, `213`

### Runtime decision logic
- Distance is computed using Euclidean distance (`Math.hypot`) in:
  - `smali/v2/a.smali` lines `2568-2583`
- In the tracker touch path (`f7/*`), no `ViewConfiguration.getScaledTouchSlop()` usage was found; slop is app-defined.
- On `ACTION_DOWN` tracker enters "tap candidate" state (`x=1`):
  - `smali/f7/l.smali` line `829`
- On `ACTION_MOVE`, if distance from initial touch point exceeds `v`, state switches to drag (`x=2`) and long-tap timer is canceled:
  - `smali/f7/l.smali` lines `1192-1216`
- On `ACTION_UP`, click is dispatched only if:
  - state is still tap-candidate (`x==1`)
  - distance `<= v`
  - evidence: `smali/f7/l.smali` lines `1422-1448`
- Click dispatch path after passing threshold:
  - `d7/b.l()` -> `services/statics/i.a(...)`
  - `smali/d7/b.smali` lines `1379-1412`, `smali/com/quickcursor/services/statics/i.smali` lines `50-124`

Pseudo-flow:

```text
down:   x = 1, save start point
move:   if hypot(curr - start) > clickDistanceThreshold => x = 2 (drag)
up:     if x == 1 and hypot(curr - start) <= clickDistanceThreshold => click()
```

## 2) Tap Timing Sensitivity (long/double tap windows)

These are separate from distance slop, but directly affect click/tap behavior.

### Keys and defaults
- `longTapTrackerThreshold` (default `400ms`)
- `doubleTapTrackerThreshold` (default `200ms`)
  - Keys in repository:
    - `smali/com/quickcursor/repositories/c.smali` lines `1609-1617`, `1641-1649`
  - Defaults/ranges in resources:
    - `res/values/integers.xml` lines `33`, `71`, `81`, `20`, `68`, `80`
  - Settings UI:
    - `res/xml/preferences_tap_behaviour.xml` lines `8-9`

### Where timers are wired
- Thresholds are read in tracker constructor:
  - `smali/f7/l.smali` lines `97-106`
- Timers are built (`B` and `C`):
  - `smali/f7/l.smali` lines `226-250`
- Timer behavior:
  - `B` runnable promotes state into long-tap behavior path: `smali/f7/h.smali` lines `69-137`
  - `C` runnable can dispatch click path: `smali/f7/h.smali` lines `49-67`

## 3) Movement Sensitivity ("Cursor Speed")

This is not click slop. It controls cursor movement gain by changing mapping geometry.

### Settings pipeline
- UI keys:
  - `triggerLength`, `cursorSpeed` in simple trigger settings:
    - `res/xml/preferences_simple_triggers_general_portrait.xml` lines `9`, `12`
- Model fields:
  - `cursorSpeed` and `triggerLength` in `Ll7/e`:
    - `smali/l7/e.smali` fields at lines `8`, `14`; getters at `242-247`, `322-327`
- During apply, values are packed into `Lk7/d`:
  - `smali/androidx/activity/p.smali` lines `641-679`
  - `smali/k7/d.smali` constructor fields `a..g` lines `22-39`

### How speed is used
- In `Ls7/a.w(...)`, `k7/d.d` (cursor speed) is mapped to divisor values:
  - `1.5, 1.75, 2.0, 2.33, 2.66, 3.0, 3.33, 3.66, 4.0, 4.33, 4.66, 5.0`
  - `smali/s7/a.smali`:
    - mapping switch + constants: lines `341-405`, `602-666`
    - switch tables: lines `867-897`
- Then cursor area dimensions are divided by that divisor:
  - `smali/s7/a.smali` lines `406-421`, `667-681`
- Result: higher cursorSpeed index => smaller control area => higher effective pointer gain/sensitivity.

UI label alignment:
- `res/values/arrays.xml` line `455` (`cursor_speeds`) shows labels from `1.5x` to `5x`.

## 4) Important Distinction: Slop vs Visual Sizes

These are often confused but are different:
- Click slop key: `clickDistanceThreshold`
- Tracker visual size keys: `trackerSize`, `trackerActionsInsideSize`, `trackerActionsOutsideSize`, `trackerActionsStrokeSize`
  - key declarations in `smali/com/quickcursor/repositories/c.smali` lines `609`, `1281`, `1317`, `1353`

Changing tracker/trigger visual sizes does not change click slop unless `clickDistanceThreshold` is changed.

## 5) Practical Tuning Notes

- To reduce accidental drags when tapping:
  - increase `clickDistanceThreshold` moderately (px-based threshold in runtime).
- To make tap recognition stricter:
  - reduce `clickDistanceThreshold`.
- To change pointer sensitivity:
  - adjust `cursorSpeed` (not `clickDistanceThreshold`).
- To change long/double tap feel:
  - adjust `longTapTrackerThreshold` and `doubleTapTrackerThreshold`.

## Bottom line

Quick Cursor does **not** rely on Android's default `touchSlop` for tracker click detection. It uses its own configurable Euclidean-distance threshold (`clickDistanceThreshold`) plus independent long/double-tap timers. Movement sensitivity is handled separately via cursor-area remapping driven by `cursorSpeed`.
