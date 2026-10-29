# CadenceResolver.kt

**Path:** `.../tracker/algorithms/cadence/CadenceResolver.kt` · **Layer:** algorithm (cadence)

## Purpose
Picks ONE cadence source: a healthy external sensor > the phone estimate > none (`CadenceSource.EXTERNAL / PHONE / NONE`). Values are never mixed.

## Rules
- Health is connection + freshness, never the value: an external `0` is a measurement and keeps the source (it must not fall back to the phone).
- A disconnect drops the external value at once. A stale one (`externalStaleAfterMs`) is dropped too.
- Recovery hysteresis: after a (re)connect or a stale spell the external source needs `externalRecoveryMinSamples` valid values over `externalRecoveryMinMs` before it is preferred again (no flapping with an intermittent sensor).
- The phone estimate is used while it is no older than `phoneStaleAfterMs`; a negative one takes no part.

## Configuration (`CadenceResolverConfig`, starting values)
`externalStaleAfterMs` 5000, `phoneStaleAfterMs` 3000, `externalRecoveryMinSamples` 2, `externalRecoveryMinMs` 1000. All times come from one monotonic clock. Not thread-safe.
