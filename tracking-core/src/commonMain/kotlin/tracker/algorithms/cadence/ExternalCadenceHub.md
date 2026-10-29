# ExternalCadenceHub.kt

**Path:** `.../tracker/algorithms/cadence/ExternalCadenceHub.kt` · **Layer:** algorithm (cadence)

## Purpose
What a native engine holds for a cadence that does not come from the phone. It takes what the HOST pushes (raw CSC notifications, a ready rpm, the connection state), runs `CscCadenceParser` and `CadenceResolver`, and answers with the chosen cadence together with the phone estimate. The package never scans, pairs or reconnects.

## API
`onConnection(sourceId, connectionId, connected)`, `onCsc(sourceId, connectionId, bytes, nowMs)`, `onRpm(sourceId, connectionId, rpm, nowMs)` (0..300, 0 is a measurement), `resolve(phoneRpm, nowMs): CadenceChoice`, `hasExternalSource()`, `reset()`.

## Identity
A source is `(sourceId, connectionId)`; the host bumps `connectionId` for every new connection. A different identity replaces the current one (parser baseline and recovery progress reset); data of an older connection of the same source, or of a closed one, is dropped. Data of a not yet known identity is an implicit connection.

Live only, never journaled. The engine calls it under its own lock/queue.
