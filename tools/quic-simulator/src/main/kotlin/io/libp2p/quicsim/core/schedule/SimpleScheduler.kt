package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO


interface SimpleScheduler {

    fun executeAfterDelay(delay: Duration, task: Runnable)

}
