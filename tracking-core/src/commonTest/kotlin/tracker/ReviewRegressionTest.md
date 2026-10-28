# ReviewRegressionTest.kt

Behavioral regressions against commit 74e1cd7. Helpers `e()` and `b()` create a running engine and bridge with a one-metre initial stride. Each test is independent and uses deterministic fake monotonic time from TestSupport.

| Test | Invariant |
|---|---|
| closedCounterDoesNotAddSteps | Device counter input after close cannot change totals. |
| closedSegmentCountDoesNotAddSteps | Segment-relative input after close cannot change totals. |
| correctionBeforeAnyCloseIsIgnored | A restored total cannot acquire a fictitious closed segment. |
| wrongSegmentDoesNotMutateBridgeTotal | Reject stale segment input before accounting. |
| wrongCounterDoesNotSetBaselineOfNewSegment | A stale event cannot seed the new baseline. |
| pausedBridgeDoesNotMutateTotal | Paused callbacks cannot count steps. |
| stepBeforeLegDoesNotMutateBridge | Measurement before leg start cannot mutate accounting. |
| directStepsBeforeLegDoNotCreatePending | The pure engine enforces the same time boundary. |
| shortWeakPeriodDoesNotLeakPendingIntoNextGap | A later outage cannot reuse earlier pending distance. |
| jumpUsesMostRecentHealthyObservation | Standing for a long time cannot relax the next sample's speed gate. |
| shortRealOutageAfterLongStandstillCanBridge | Gap duration and origin use the last healthy observation. |
| exactCorrectionStillWorksAfterDoubleClose | Preserve the owner's pause-to-finish correction fix. |

The first eleven fail on the baseline; the last is a positive control and already passes there. All twelve pass with the patch. Tests do not exercise native callback scheduling or sensor accuracy. The three distance cases also have shared CSV fixtures used by Kotlin and Dart.
