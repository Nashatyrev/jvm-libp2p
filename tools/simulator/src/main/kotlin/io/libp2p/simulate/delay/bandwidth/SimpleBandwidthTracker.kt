package io.libp2p.simulate.delay.bandwidth

import io.libp2p.simulate.Bandwidth
import io.libp2p.tools.delayedFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class SimpleBandwidthTracker(
    totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService
) : FifoBandwidthDelayer(totalBandwidth) {

    override fun delay(messageSize: Long): CompletableFuture<Unit> =
        executor.delayedFuture(totalBandwidth.getTransmitTime(messageSize))
}
