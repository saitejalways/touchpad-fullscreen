# Android Touchpad App (ADB-Only Injection): Requirements and Architecture

Date: 2026-03-05  
Scope: `Accessibility overlay + custom touchpad logic + ADB mouse-style injection as the only output path`

## 1. Objective

Build a non-root Android app that uses the phone screen as a touchpad and sends pointer/click actions through an ADB injection channel.

Important: there is no standalone input backend.  
If ADB is disconnected, pointer output is unavailable by design.

## 2. Hard Constraints

1. No root.
2. No OEM/system signing.
3. No privileged permissions (`INJECT_EVENTS`, `DISABLE_INPUT_DEVICE`, `CREATE_VIRTUAL_DEVICE`).
4. Sideload/non-Play distribution is acceptable.
5. Touch suppression is app-level (overlay capture).

## 3. Feasibility Statement

1. ADB-only architecture is feasible for power-user and managed deployments.
2. It is not equivalent to a system virtual HID mouse owned by the app.
3. Reliability depends on maintaining an ADB control channel.
4. This can be production-usable only if onboarding, reconnection, and failure UX are engineered carefully.

## 4. Product Behavior

## 4.1 Runtime Modes

1. `Disconnected`
- Overlay disabled but no output injection.
- UI must clearly show "ADB not connected".

2. `Armed`
- ADB connected and ready.
- Overlay touchpad can be enabled (or disabled).

3. `Active`
- Overlay captures touch input.
- Touch deltas are converted to ADB injection commands.

4. `Fault`
- Injection failures exceed threshold.
- App auto-disables touchpad capture to avoid trapping user input.

## 4.2 User Promise

1. Touchpad is static and full-screen.
2. Cursor movement is relative and persistent between swipes.
3. Edge clamping is strict and symmetric.
4. Rotation does not silently disable the system.
5. If ADB drops, app exits active mode safely and notifies immediately.

## 5. Functional Requirements

## 5.1 Core Touchpad Engine

1. Relative delta tracking from captured touch stream.
2. Configurable sensitivity (speed multiplier + optional curve).
3. Authoritative clamping in logical coordinate space.
4. No edge overshoot dead-zone (top/left/right/bottom).
5. Persistent cursor state across lift/re-touch.

## 5.2 Overlay and Touch Capture

1. Full-screen touch capture layer based on accessibility overlay approach.
2. Explicit enable/disable affordance always available. How exactly up for discussion.
3. Rotation-safe re-layout and metric recomputation.
4. **Haptic** acknowledgement on enable/disable/fault.

## 5.3 ADB Injection (Mandatory Path)

1. All output actions route to ADB backend.
2. Supported commands at minimum:
- move,
- click/down/up,
- scroll via two finger movement (classic touchpad behavior).
 - touch contact lifecycle (`TOUCH_CONTACT`) for edge-scroll restore timing.
 - raw one-finger touch deltas (`TOUCH_DELTA`) for edge-push activation distance.
3. Injection queue with rate limit and backpressure.
4. Connection watchdog + heartbeat.
5. Fast reconnect strategy with bounded retries.

## 5.4 Setup and Lifecycle

1. Guided setup for wireless debugging / ADB pairing assumptions.
2. Persist endpoint profile(s).
3. Preflight checks before entering active mode.
4. Immediate safe shutdown on endpoint loss.

## 6. Non-Functional Requirements

1. End-to-end movement latency target: <= 25 ms median in stable connection conditions.
2. No ANR/crash during rotation, lock/unlock, app switching.
3. Bounded CPU usage under sustained movement load.
4. Diagnostic logging with redaction controls.
5. Deterministic state machine; no hidden mode transitions.

## 7. High-Level Architecture

## 7.1 Components

1. `AccessibilityServiceHost`
- Owns overlay lifecycle and accessibility binding state.

2. `OverlayController`
- Creates full-screen capture surface.
- Handles configuration changes and safe teardown.

3. `TouchpadEngine`
- Delta computation, filtering, speed transform, clamping.

4. `CursorStateStore`
- Thread-safe authoritative logical pointer state.

5. `ActionRouter`
- Converts logical actions to backend command objects.
- Single backend: `AdbInjectionBackend`.

6. `AdbSessionManager`
- Pair/connect/auth/session keepalive/reconnect.

7. `AdbInjectionBackend`
- Serializes commands and executes transport writes.
- Handles retry policy and failure escalation.

8. `SafetyController`
- Fault thresholds, auto-disable logic, emergency release.

9. `SettingsModule`
- Sensitivity, trigger, fail-safe, connection preferences.

10. `FeedbackModule`
- Haptics, notification state, status banners.

## 7.2 Data Path

1. Touch event -> `OverlayController`.
2. Motion stream -> `TouchpadEngine` -> clamped logical position.
3. Logical action -> `ActionRouter`.
4. Command object -> `AdbInjectionBackend` queue.
5. Transport send -> ADB endpoint -> Android input shell/service path.
6. Success/failure -> `SafetyController` + UI status update.

## 8. ADB Transport Design

## 8.1 Deployment Topologies

1. `External Companion` (recommended)
- Phone app sends compact commands to a companion process.
- Companion executes ADB commands against target device.
- Better observability and easier support.

2. `On-device localhost` (allowed but fragile)
- Phone app connects to local ADB endpoint if user setup permits.
- More brittle across devices and updates.

Architecture remains ADB-only in both topologies.

## 8.2 Command Protocol

Required message types:
1. `MOVE_REL dx dy seq ts`
2. `BUTTON_DOWN button seq ts`
3. `BUTTON_UP button seq ts`
4. `CLICK button seq ts`
5. `SCROLL dx dy seq ts`
6. `PING/PONG`

Protocol requirements:
1. Monotonic sequence numbers.
2. Timestamps for jitter analysis.
3. Coalescing for dense move streams.
4. Backpressure: drop stale movement commands first, never reorder click edges.

## 8.4 Edge Scroll Activation and Calibration

1. One-finger edge scroll activation is based on accumulated finger push distance beyond the cursor edge hit, measured in px.
2. Active edge-scroll mode reuses the HID wheel path and can scroll in both directions while the cursor is logically pinned for scroll anchoring.
3. Experimental cursor-center calibration can estimate relative HID units per screen pixel so the app can better snap to center and restore on touch liftoff.
4. Calibration quality is device-dependent because the HID device remains relative, not absolute.

## 8.3 Failure Policy

1. N consecutive send failures -> enter `Fault`.
2. In `Fault`, overlay capture auto-disables.
3. Notify user with clear recovery action.
4. Re-enter `Armed` only after successful health checks.

## 9. Security and Safety Requirements

1. User-consent gate before first ADB activation.
2. Persistent foreground notification while active.
3. One-tap emergency disable action in notification.
4. No storage of secrets in plain text.
5. Session timeout for stale idle links.

## 10. Implementation Plan and Effort

## 10.1 Phase 1: ADB-Core MVP

Scope:
1. Overlay capture + touchpad engine.
2. Cursor state, clamping, sensitivity.
3. Single ADB backend with command queue.
4. Basic setup wizard and status UI.
5. Fault auto-disable.

Effort:
- 1 Android engineer + 1 tooling engineer: ~5-8 weeks.
- Solo engineer: ~8-12 weeks.

## 10.2 Phase 2: Reliability Hardening

Scope:
1. Reconnect/backoff tuning.
2. Rotation/lifecycle stress fixes.
3. Telemetry and diagnostics pack.
4. OEM compatibility adaptations.

Effort: ~4-7 weeks.

## 10.3 Phase 3: UX and Operability

Scope:
1. Better onboarding and troubleshooting UX.
2. Profile management for multiple endpoints.
3. Advanced tuning presets.

Effort: ~3-5 weeks.

Total program:
- ADB-only production candidate: ~9-15 weeks (2 engineers) or ~12-19 weeks (solo).

## 11. Risks and Mitigations

1. ADB link instability.
- Mitigation: watchdog, health checks, deterministic fault handling.

2. User lockout risk while overlay capturing.
- Mitigation: hard emergency disable path and fault auto-release.

3. OEM behavior variance.
- Mitigation: feature flags, compatibility matrix, conservative defaults.

4. Latency spikes on command-heavy movement.
- Mitigation: delta coalescing, transport batching, adaptive smoothing.

5. Setup complexity.
- Mitigation: step-by-step wizard with live validation.

## 12. Acceptance Criteria

1. App never traps user input irrecoverably.
2. Rotation does not disable service unexpectedly.
3. Cursor clamping has no asymmetric dead-zone.
4. ADB drop causes safe auto-disable within configured timeout.
5. Reconnect can restore `Armed` state without app restart.
6. 30-minute stress test without crash or stuck active mode.

## 13. Out of Scope

1. Privileged/system virtual mouse device creation.
2. Hardware touchscreen disable at driver/system level.
3. Non-ADB injection backends.
