package io.libp2p.quicsim.core.schedule.impl

import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.TimePoint
import io.libp2p.quicsim.core.schedule.impl.NanoTimePoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

object CPUMonotonicTimer : MonotonicTimer {
    override fun time(): TimePoint = NanoTimePoint(System.nanoTime())
}

data class NanoTimePoint(val nanos: Long) : TimePoint {
    override fun minus(other: TimePoint): Duration =
        (nanos - (other as NanoTimePoint).nanos).nanoseconds
}