

Clocks: a recovery measures the dead interval against a ClockAnchor, one pair (engine time, calendar time) of the SAME instant (a tick, a header, a fix's measurement time, or a previous recovery), never against the time an event was received. `applyRecovered` is the single path for live and replay; a recording leg gets an explicit unmeasured interval (`markInterruption`) unless a GPS gap was already open.
