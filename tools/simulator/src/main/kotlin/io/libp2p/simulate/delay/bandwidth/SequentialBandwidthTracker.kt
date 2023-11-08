package io.libp2p.simulate.delay.bandwidth

import io.libp2p.simulate.Bandwidth
import io.libp2p.tools.delayedFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class SequentialBandwidthTracker(
    totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService
) : FifoBandwidthDelayer(totalBandwidth) {

    private var lastMessageFuture: CompletableFuture<Unit> = CompletableFuture.completedFuture(null)

    override fun delay(size: Long): CompletableFuture<Unit> {
        lastMessageFuture = lastMessageFuture.thenCompose {
            executor.delayedFuture(totalBandwidth.getTransmitTime(size))
        }
        return lastMessageFuture
    }
}
