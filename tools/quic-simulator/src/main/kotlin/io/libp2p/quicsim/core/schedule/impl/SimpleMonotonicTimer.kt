package io.libp2p.quicsim.core.schedule.impl

import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.TimePoint
import kotlin.time.Duration

class SimpleMonotonicTimer : MonotonicTimer {
    override val startTime: TimePoint = SimpleTimePoint(Duration.ZERO)
    var curT: Duration = Duration.ZERO

    override fun time(): TimePoint = SimpleTimePoint(curT)

    data class SimpleTimePoint(val t: Duration) : TimePoint {
        override fun minus(other: TimePoint): Duration = t - (other as SimpleTimePoint).t
    }
}