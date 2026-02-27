package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

fun SimpleScheduler.executeAtFixedRate(
    initialDelay: Duration = ZERO,
    period: Duration,
    task: Runnable
) {
    require(!initialDelay.isNegative()) { "initialDelay must be non-negative" }
    require(period > ZERO) { "period must be positive" }

    fun scheduleNext(delay: Duration) {
        executeAfterDelay(delay) {
            task.run()
            scheduleNext(period)
        }
    }

    scheduleNext(initialDelay)
}
