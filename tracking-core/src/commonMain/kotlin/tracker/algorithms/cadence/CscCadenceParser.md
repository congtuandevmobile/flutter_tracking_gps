# CscCadenceParser.kt

**Path:** `.../tracker/algorithms/cadence/CscCadenceParser.kt` · **Layer:** algorithm (cadence)

## Purpose
Decodes the Bluetooth CSC Measurement characteristic (0x2A5B) pushed by the host and turns the crank counters into a cadence. No Bluetooth, no clock of its own, not journaled, no effect on distance.

## Answers
`onMeasurement` / `onCrank` return an rpm, `ZERO` (cranks verified stopped) or `UNKNOWN` (negative). `rpm(nowMs)` returns the last answer while notifications keep arriving, `UNKNOWN` after `staleMs` (never `ZERO`).

## Rules
- Packet: flags (bit 0 wheel, bit 1 crank), wheel revolutions (uint32) + wheel event time (uint16) if bit 0, crank revolutions (uint16) + last crank event time (uint16, 1/1024 s) if bit 1. Wheel-only, empty and truncated packets are ignored (state untouched).
- A value is computed between two consecutive crank events, so both 16-bit counters may wrap.
- Stopped: valid notifications with an unchanged counter for `max(zeroAfterMs, 2 x last revolution time)`. No notification is not stopped.
- New baseline (no value) when: the first packet of a connection, a silence of `silenceResetMs`, the previous revolution `eventClockSpanMs` (the 64 s wrap) or more in the past, a counter jump the elapsed time cannot explain at `maxRpm`, a clock that goes back, no event time progress.
- A value outside `minRpm..maxRpm` is not a cadence. The first revolution after a long coast averages over the coast and is therefore usually rejected; the next one is right.
- `reset()` for every new connection: the last sample of an old connection is never compared with the first of a new one.

## Parameters (starting values, not measured)
`staleMs` 5000, `zeroAfterMs` 3000, `minRpm` 15, `maxRpm` 220, `silenceResetMs` 15000, `eventClockSpanMs` 60000.

`CscCadenceParser.standard()` is for Swift (no Kotlin default arguments). Not thread-safe.
