# Android Scrolling Research (2026-03-06)

## Scope
This note focuses on Android-native guidance for:
- fling physics
- smooth frame-synced scrolling
- velocity estimation
- practical constraints for custom gesture pipelines

## Primary Sources
- OverScroller API: https://developer.android.com/reference/android/widget/OverScroller
- VelocityTracker API: https://developer.android.com/reference/android/view/VelocityTracker
- ViewConfiguration API: https://developer.android.com/reference/android/view/ViewConfiguration
- MotionEvent batching/historical samples: https://developer.android.com/reference/android/view/MotionEvent
- Choreographer frame callbacks: https://developer.android.com/reference/android/view/Choreographer

## Key Findings

### 1) Fling should use velocity + friction, not fixed-distance timers
- `OverScroller.fling(...)` accepts initial velocity in px/s and computes deceleration over time.
- `OverScroller.setFriction(...)` controls inertia; lower friction means longer fling.
- Android default friction comes from `ViewConfiguration.getScrollFriction()`.

Practical takeaway:
- Use velocity-driven fling.
- Map user "inertia" setting to friction scaling (not ad-hoc tail delays).

### 2) Velocity should be computed with proper units and bounds
- `VelocityTracker.computeCurrentVelocity(units, maxVelocity)` provides velocity in selected units.
- Android exposes min/max fling velocity via `ViewConfiguration`.

Practical takeaway:
- Gate fling by minimum velocity threshold to avoid accidental micro-flings.
- Clamp to maximum velocity to avoid runaway behavior.

### 3) Smoothness depends on frame pacing and sample processing
- `MotionEvent.ACTION_MOVE` may contain historical/batched samples.
- Processing historical samples improves temporal fidelity.
- Frame-synced dispatch (`Choreographer.postFrameCallback`) aligns updates to render cadence.

Practical takeaway:
- Avoid bursty "send-all-now" behavior.
- Dispatch scroll deltas at a steady high rate (frame-aligned or fixed short interval).
- Prefer accumulating pending deltas and draining in small chunks each tick.

### 4) Stop behavior must be explicit and immediate when no fling is active
- If fling is disabled or below threshold, scrolling should end with no tail.
- Queueing layers must not leave stale scroll deltas after release.

Practical takeaway:
- On release with no fling: flush/clear pending deltas and emit a stop marker.
- On release with fling: let fling own post-release deltas and emit stop when finished.

## Implementation Guidance For This Project
- Keep live-scroll dispatch at a short interval (target ~8ms) with bounded per-tick chunking.
- Introduce a fling action carrying initial velocity and user-selected inertia.
- Implement fling stepping through `OverScroller`, feeding per-tick delta output into the same scroll dispatch queue.
- Keep vertical/horizontal multipliers independent.
- Preserve current right-click and two-finger grace semantics.
