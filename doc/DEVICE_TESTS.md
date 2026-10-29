# Device test protocol

Unit tests prove the rules and the invariants; they do NOT prove a sensor, the background or the
battery. Nothing below has been run. Run it before trusting the numbers, and write the results
next to this file. Every threshold in `TrackingConfig` is a starting value to be tuned from these.

## Devices
At least: one recent and one low-end Android (and one Samsung or Xiaomi for the vendor battery
managers), one iPhone on the oldest supported iOS and one on the newest.

## 1. Distance accuracy (open sky)
Measured course of known length (a 400 m track, or a wheel). Run 5 times at a walk and at a run.
Record: reported `totalMeters` vs true, `quality`, `fixCount`. Pass: error inside the tolerance
you choose (state it first).

## 2. Weak GPS (the case this SDK is built around)
Walk the SAME known stretch (a covered passage, a tunnel, or indoors) with GPS weak or lost.
Record `bridgedGap`, `uncreditedGap`, `pendingPdrMeters`, `pdrMeters`, `ambiguousSteps`,
`distanceEligibleSteps`. Check the stride that was learned on open sky against the true stride.
Repeat with the phone SHAKEN in the hand on the spot: distance must not appear (conservative
policy) and `uncreditedGap` must show the time.

## 3. Standing still and drift
Phone on a table 10 minutes outdoors, then indoors by a window. Pass: `totalMeters` stays at
0; if it does not, note the speed of the drift against `stationarySpeedMps` (the pinned limit).

## 4. Background and the OS
Start, lock the screen, put the app in the background for 30 and 60 minutes, then return. Android:
vendor battery management ON and OFF; swipe the app from recents while recording. iOS: with and
without "Always" permission. Pass: the recording has no hole bigger than the sampling interval;
note any hole, its length and the device.

## 5. Process death and restore
Kill the app (Android: force stop and swipe; iOS: swipe) mid-recording; open it and `restore`. Pass:
`restoreState == complete` (or `tailRepaired` after a kill mid-write), the same distance, steps,
stride, `activeTime`, segment; the session is paused. Repeat after a REBOOT: `activeTime` must not
be negative or jump. Android: after restore, `resume` from the foreground and from the background
(`FOREGROUND_START_FAILED` is the expected, clean outcome from the background on newer Android).

## 6. Finish
Finish right after a pause, while a fix is arriving, and in quick succession twice. iOS: with Motion
permission denied, revoked mid-run, and the Info.plist key missing. Pass: one final snapshot,
`finishing` then `finished`, `stepsFinal` true only when the exact count was obtained.

## 7. Presenter
A presenter whose `onUpdate` takes 10 s, and one that never returns. Pass: the recording is
unaffected, no two adapter calls overlap, `dispose` returns.

## 8. Battery
One hour, screen off, adaptive sampling off and on, `batterySaver` off and on. Record the battery
percentage and the number of fixes. (`highAccuracy` of the sampling plan is NOT applied natively yet.)

## 9. Durability
Kill the process, then (device lab) cut the power, at a random moment. Pass: at most the events that
`journalPending` reported are lost.

## 10. Pedal cadence (accelerometer)
With `createSession(pedalCadence: true)` on a real bike: phone in the trouser pocket, strapped to the thigh, and (as the negative case) on the handlebar. Record 5 minutes at steady 60, 80 and 100 rpm against a BLE cadence sensor or a watch, plus a ramp, a stop and coasting. Record `pedalCadenceRpm` against the reference, how long it takes to appear, how long it stays unknown after coasting, and the battery over one hour with and without it. Pass: you choose the tolerance first; every threshold of `PedalCadenceEstimator` is a starting value to tune from these recordings.

## 11. External cadence sensor

Needs a BLE crank cadence sensor and a host that connects to it and pushes its notifications. Not tested yet.

1. Connect, push notifications at a steady cadence: `cadenceSource` becomes `externalSensor` after two values and `pedalCadenceRpm` matches the sensor's own display within 2 rpm.
2. Stop pedalling with the bike rolling: `pedalCadenceRpm` becomes 0 after about 3 s, still `externalSensor`, even with `pedalCadence: true` and the phone on the leg.
3. Switch the sensor off or move out of range: with the connection reported lost the cadence falls back to the phone estimate (or null) at once; with only silence it becomes null after 5 s, never 0.
4. Reconnect: the first valid value after a reconnect must not use the old counters (no spike); the source returns after two values.
5. Lock the screen for 30 minutes while the host keeps the connection: note whether notifications still arrive on each OS.
6. Kill the host's Flutter engine while recording continues: the cadence must fall back, not freeze.
7. A sensor that sends wheel-only packets (speed sensor): no cadence.
