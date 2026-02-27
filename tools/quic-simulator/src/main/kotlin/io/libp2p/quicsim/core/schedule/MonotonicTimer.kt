package io.libp2p.quicsim.core.schedule

interface MonotonicTimer {
    fun time(): TimePoint
}
