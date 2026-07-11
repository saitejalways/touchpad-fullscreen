# Edge Scrolling Technical Specification

## 1. Objective
Define a deterministic, low-latency edge-scrolling behavior that:
- Uses focus-based `input roll` for smooth, cursor-position-independent scrolling.
- Translates finger movement directly to trackball-like scroll events.
- Never blocks cursor movement.
- Uses `dumpsys input` cursor ground truth for edge detection.

This spec is for the standalone on-device ADB architecture currently used in this project.

## 2. Scope
In scope:
- One-finger edge push detection and routing.
- Scroll event generation via `input roll` (trackball simulation).
- Edge entry delay and sustained scroll behavior.
- Haptic behavior for edge hit.
- Debug/highlight behavior for active edge.
- Test and acceptance criteria.

Out of scope:
- Two-finger gesture recognition itself (uses separate HID scroll path).
- Overlay-based touch interception redesign.
- New transport protocol changes.

## 3. Definitions
- **Ground truth cursor**: pointer position parsed from `adb shell dumpsys input`.
- **Edge push**: user movement vector pushes into the same screen edge where cursor currently is.
- **Strict edge**: cursor is considered at edge only when it is at most `1px` from that edge.
- **Corner deadzone**: configurable corner region where edge scroll is blocked.
- **`input swipe`**: Android shell command that injects touch swipe gestures at specific coordinates.

## 4. Functional Requirements

### 4.1 Source of Truth
- Edge detection MUST use `dumpsys input` cursor coordinates.
- No optimistic cursor simulation is allowed for edge eligibility decisions.
- If ground truth is stale/unavailable, edge scroll MUST be skipped (normal move still runs).

### 4.2 Edge Eligibility
- Edge scrolling requires all conditions:
1. One-finger edge scroll setting enabled.
2. Ground truth cursor available from last update.
3. Cursor at strict edge (`<= 1px` to the relevant boundary).
4. Pointer movement direction is into that same edge.
5. Cursor not in corner deadzone for that edge.

- Edge scroll direction is determined by the push direction:
  - Top edge: upward push (`dy < 0`).
  - Bottom edge: downward push (`dy > 0`).
  - Left edge: leftward push (`dx < 0`).
  - Right edge: rightward push (`dx > 0`).

### 4.3 Movement + Scroll Routing
- Cursor movement deltas MUST NOT be discarded while edge scrolling.
- Move and scroll can be emitted in the same frame.
- Edge routing outputs:
  - `moveDx/moveDy` unchanged for pointer movement.
  - `input roll` commands only when edge-push conditions are met.

### 4.4 Scroll Execution Path Separation
- **Edge scroll** uses `input swipe` (touch gesture at screen center).
- **Two-finger scroll** uses HID wheel events (cursor-position-based).
- These paths are intentionally different:
  - Edge scroll: `input swipe <x1> <y1> <x2> <y2> <duration>` via ADB shell at screen center.
  - Two-finger scroll: HID relative wheel events via `/dev/hidg0`.

### 4.5 Edge Entry Delay and Sustained Scroll
- On first hit of the screen edge:
  - Fire edge-hit haptic.
  - Start entry delay timer (`edge_scroll_repeat_delay_ms`).
  - Accumulate movement deltas but do NOT emit scroll yet.
- After delay expires AND user continues pushing:
  - Emit `input roll` with scaled movement deltas.
  - Reset accumulators.
- If cursor leaves edge-push state:
  - Clear accumulators.
  - Reset delay timer.

### 4.6 Scroll Factor Scaling
- Edge scroll uses configurable scaling factors:
  - `edge_scroll_vertical_factor`: multiplies horizontal movement (`dx`) for vertical scroll (top/bottom edges).
  - `edge_scroll_horizontal_factor`: multiplies vertical movement (`dy`) for horizontal scroll (left/right edges).
- Default: `0.5` for both (finger movement is halved for scroll).
- Axis mapping:
  - Top/bottom edges: `swipeDy = moveDx * verticalFactor` (horizontal finger movement → vertical scroll).
  - Left/right edges: `swipeDx = moveDy * horizontalFactor` (vertical finger movement → horizontal scroll).

### 4.7 Haptics
- Edge-hit haptic:
  - Fired once when entering edge state (cursor touches edge).
  - Controlled by dedicated intensity setting (`haptic_edge_hit_intensity`).
- Scroll-step haptic:
  - NOT fired for edge scroll (input roll is continuous, not step-based).
  - Only fires for two-finger HID scroll steps.

### 4.8 Edge Highlight
- Edge highlight MUST be derived from ground truth cursor position.
- Only the currently active edge side is highlighted.
- Corners respect corner deadzone suppression.
- Highlight width is configurable and visual only; strict edge trigger remains `1px`.

## 5. Performance Requirements
- Move path MUST be non-blocking.
- `input roll` commands are synchronous shell invocations but MUST NOT block the move path.
- Cursor responsiveness near edges must remain comparable to non-edge movement.
- Ground truth polling runs in background at configurable interval (`sync_interval_ms`).
- Poller uses last available ground truth information if it times out or is unavailable.

## 6. Failure Behavior
- If `dumpsys` parse fails, times out, or returns stale data:
  - Use previous ground truth.
- If `input roll` shell command fails:
  - Log warning but do not crash or block input pipeline.
  - Continue with cursor movement.

## 7. Settings Contract (Edge Scrolling)
- `one_finger_edge_scroll_enabled` (bool)
- `sync_interval_ms` (int)
- `edge_thickness_px` (float, visual highlight thickness)
- `edge_corner_deadzone_px` (float)
- `edge_scroll_repeat_delay_ms` (int, entry delay before scrolling starts)
- `edge_scroll_vertical_factor` (float, default 0.5)
- `edge_scroll_horizontal_factor` (float, default 0.5)
- `haptic_edge_hit_intensity` (0..255)

## 8. Acceptance Criteria

### 8.1 Behavior
1. Cursor touches top/bottom/left/right edge → haptic feedback fires.
2. After `edge_scroll_repeat_delay_ms` of sustained push → `input roll` events start.
3. Scroll speed proportional to finger speed × configured factor.
4. Leaving edge resets delay; re-entering restarts delay.
5. Corner deadzone blocks edge scrolling in corners.
6. Direction mismatch does not scroll (example: at top edge, pushing down does not scroll).
7. Two-finger scroll continues to use HID path (unchanged).

### 8.2 Performance
1. No hard lag/stutter when pushing at any screen edge.
2. Cursor remains responsive while edge scroll is active.
3. `input roll` commands do not block move events.

### 8.3 Observability
1. Logs show edge-route decisions and `input roll` emissions.
2. Edge highlight reflects active edge from ground truth updates.
3. Debug overlay shows `input roll` commands with timestamps.

## 9. Implementation Details

### 9.1 Edge Detection State Machine
```
State: NOT_AT_EDGE
  → Cursor at edge: ENTERED_EDGE (fire haptic, start delay, clear accumulators)

State: ENTERED_EDGE (delay running)
  → Delay expired + still pushing: SUSTAINED_SCROLL (emit input roll)
  → Cursor leaves edge: NOT_AT_EDGE (clear accumulators)

State: SUSTAINED_SCROLL
  → Continue pushing: emit input roll each frame
  → Cursor leaves edge: NOT_AT_EDGE (clear accumulators)
```

### 9.2 `input swipe` Command Format
```bash
adb shell input swipe <x1> <y1> <x2> <y2> <duration_ms>
```
- `<x1>, <y1>`: start coordinates (screen center: `width/2, height/2`).
- `<x2>, <y2>`: end coordinates (center + scaled delta).
- `<duration_ms>`: swipe duration (50ms for responsive feel).
- Swipe always originates from screen center to scroll the visible content.

### 9.3 Scroll Accumulation During Delay
```kotlin
// During entry delay:
edgeScrollAccumulatedDx += moveDx
edgeScrollAccumulatedDy += moveDy

// After delay expires:
rollDx = (moveDx * horizontalFactor).toInt()
rollDy = (moveDy * verticalFactor).toInt()
executeEdgeScrollRoll(rollDx, rollDy)
edgeScrollAccumulatedDx = 0f
edgeScrollAccumulatedDy = 0f
```

## 10. Test Plan

### 10.1 Unit/Instrumentation Tests
- Edge routing direction tests for all four edges.
- Entry delay timing tests.
- Scroll factor scaling tests.
- Corner deadzone suppression tests.
- Regression test that two-finger scroll still uses HID path.

### 10.2 Full-Cycle Device Tests
1. Enable service/backend/touch capture.
2. Move cursor to each edge and push into edge.
3. Verify:
   - Haptic fires on edge entry.
   - Delay before scroll starts.
   - Scroll direction matches push direction.
   - Scroll speed proportional to finger speed.
   - No cursor lag/stutter.
4. Test corner deadzone (scroll should not trigger).
5. Test direction mismatch (scroll should not trigger).

## 11. Device Note
Primary validation target so far: Samsung Galaxy S23 Ultra.
Behavior on other OEMs may vary due to:
- `dumpsys input` formatting differences.
- `input roll` implementation differences.
- Pointer pipeline differences.

## 12. Migration Notes
- Previous implementation used HID wheel events for edge scroll.
- New implementation uses `input roll` (focus-based, cursor-position-independent).
- Two-finger scroll remains on HID path (unchanged).
- Settings added: `edge_scroll_vertical_factor`, `edge_scroll_horizontal_factor`.
- Existing `edge_scroll_repeat_delay_ms` repurposed as entry delay (was cooldown between steps).
