package io.libp2p.quicsim.core.schedule.impl

import com.google.common.base.Supplier
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.TimePoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class NanoMonotonicTimer(
    val nanoSupplier: () -> Long
) : MonotonicTimer {
    override val startTime: TimePoint = NanoTimePoint(nanoSupplier())

    override fun time(): TimePoint = NanoTimePoint(nanoSupplier())

    companion object {
        val CPU = NanoMonotonicTimer(System::nanoTime)
    }
}

data class NanoTimePoint(val nanos: Long) : TimePoint {
    override fun minus(other: TimePoint): Duration =
        (nanos - (other as NanoTimePoint).nanos).nanoseconds
}