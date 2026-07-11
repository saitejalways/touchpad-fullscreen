# Vertical Edge Scroll Sign Investigation (2026-03-07)

## Scope
Investigated vertical edge-push scrolling inversion/non-function by exhaustively testing sign placements in:
- `TouchpadEngine` vertical scroll output (`E`)
- edge route mapping (`R`)
- executor->accumulator vertical input (`A`)

Clamp policy was kept fixed as requested:
- top edge: `vWheel >= 0`
- bottom edge: `vWheel <= 0`

## Matrix Results
All 8 sign combinations were deployed to device and manually tested. All failed in practice.

| ID | E | R | A | Result |
|---|---:|---:|---:|---|
| V0 | -1 | -1 | +1 | Fail |
| V1 | -1 | -1 | -1 | Fail |
| V2 | -1 | +1 | +1 | Fail |
| V3 | +1 | -1 | +1 | Fail |
| V4 | -1 | +1 | -1 | Fail |
| V5 | +1 | -1 | -1 | Fail |
| V6 | +1 | +1 | +1 | Fail |
| V7 | +1 | +1 | -1 | Fail |

## Conclusion
This is not an isolated vertical sign inversion bug. Root cause is likely in one or more of:
- edge-push gating/eligibility timing (strict edge + direction + deadzone)
- edge entry/cooldown behavior
- accumulator thresholding/step emission conditions during edge route
- runtime state interaction (capture/service/cursor ground truth timing)

Next diagnostic step should be runtime trace correlation for one failed gesture:
1. `resolveEdgePushState` decision
2. `edgeEntryScrollCommand` values
3. `dispatchScroll` inputs and accumulator outputs
4. `clampEdgeWheelDirection` result
5. emitted HID wheel report
