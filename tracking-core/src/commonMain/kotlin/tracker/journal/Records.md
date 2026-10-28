

`Recovered` (code `Q`) now carries everything needed to apply it alone: target engine time, offset, gap, whether the session was recording, the next segment id, the anomaly flag and the calendar time. Replay refuses a negative gap, a clock going backwards, an overflow or a state mismatch (CORRUPT).
