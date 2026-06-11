package io.libp2p.quicsim.core.schedule

interface MonotonicTimer {

    val startTime: TimePoint

    fun time(): TimePoint

    fun elapsedTime() = time() - startTime
}
