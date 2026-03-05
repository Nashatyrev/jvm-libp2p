package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO


interface SimpleScheduler {

    fun executeAfterDelay(delay: Duration, task: Runnable)

    fun executeAtFixedRate(
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
}
