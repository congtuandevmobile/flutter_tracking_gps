

`ProcessDeathRecoveryTest`: the dead interval is added to both clocks and the recording continues in a new segment; no distance is invented and the silence is reported; a rebooted monotonic clock changes nothing; paused keeps its active time while elapsed grows; finished is untouched; a backwards calendar clock counts 0; a gap above the limit parks paused; no calendar reference falls back to a plain pause; replay reproduces the clocks and a second recovery at the same moment adds nothing.
