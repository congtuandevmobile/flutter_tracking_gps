# StepSession.kt

**Path:** `.../tracker/algorithms/motion/StepSession.kt` · **Layer:** algorithm (motion)

## Purpose
**Session-relative step accounting**, the same policy on Android and iOS, so the platforms only read their sensor and never decide what counts.

## Policy
- Steps of closed segments are **accepted** and do not change again (except `correctClosedSegment`).
- The **first device-counter reading of a segment is only a baseline**: it adds nothing, so steps taken before it, or while paused, are never counted.
- A counter that goes backwards (reboot, sensor reset) keeps the accepted total and becomes the new baseline.
- A segment-relative count (iOS `CMPedometer` counting from a start date) is cumulative within the segment; a lower reading is ignored.
- A restored total (`reset(initialSteps)`) is never added to the device counter.

## API
| Member | Description |
|---|---|
| `total` | Accepted steps of closed segments plus the open one. |
| `reset(initialSteps = 0)` | New session. |
| `openSegment()` | New segment: fresh baseline, accepted total kept. |
| `onCounter(raw): Long` | Android `TYPE_STEP_COUNTER` reading; returns the steps added to `total`. |
| `onSegmentCount(steps): Long` | iOS count since the segment start; returns the steps added. |
| `closeSegment()` | Folds the open segment into the accepted total. |
| `correctClosedSegment(exact): Long` | Replaces the just-closed segment's count with an exact one (iOS asks the OS for the range up to the boundary). Ignored once the next segment has counted steps. Changes the displayed total only. |

## Used by / tested by
`TrackingCoreBridge` (owns one). Tested by `StepsTest` (`StepSessionTest`, `BridgeStepsTest`).
