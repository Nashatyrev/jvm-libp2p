package io.libp2p.quicsim.core.schedule.impl

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.SimpleScheduler
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

fun MonotonicTimer.toCurrentTimeSupplier(): CurrentTimeSupplier {
    val epochTimePoint = this@toCurrentTimeSupplier.time()
    return {
        (this@toCurrentTimeSupplier.time() - epochTimePoint).inWholeMilliseconds
    }
}

private class ScheduledExecutorSimpleScheduler(
    val scheduledExecutorService: ScheduledExecutorService
) : SimpleScheduler {
    override fun executeAfterDelay(delay: Duration, task: Runnable) {
        scheduledExecutorService.schedule(task, delay.inWholeNanoseconds, TimeUnit.NANOSECONDS)
    }
}

fun SimpleScheduler.toScheduledExecutorService(): ScheduledExecutorService {
    require(this is ScheduledExecutorSimpleScheduler) {
        "Unsupported SimpleScheduler implementation: ${this::class.qualifiedName}"
    }
    return this.scheduledExecutorService
}

fun ScheduledExecutorService.toSimpleScheduler(): SimpleScheduler =
    ScheduledExecutorSimpleScheduler(this)
