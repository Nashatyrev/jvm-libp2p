package io.libp2p.quicsim.host

import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.SimpleScheduler

data class SimContext(
    val scheduler: SimpleScheduler,
    val timer: MonotonicTimer,
)