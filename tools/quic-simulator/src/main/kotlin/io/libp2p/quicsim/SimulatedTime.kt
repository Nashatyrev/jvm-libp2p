package io.libp2p.quicsim

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class SimulatedTime(initialMillis: Long = 0) {
    private val currentMillis = AtomicLong(initialMillis)

    fun nowMillis(): Long = currentMillis.get()

    fun advanceBy(duration: Duration): Long {
        require(!duration.isNegative) { "Duration must be non-negative" }
        return currentMillis.addAndGet(duration.toMillis())
    }
}
