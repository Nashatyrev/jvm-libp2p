package io.libp2p.simulate.delay.bandwidth

import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.SimPeerId
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class LoggingBandwidthDelayer(
    val delegate: BandwidthDelayer,
    val logger: (String) -> Unit
) : BandwidthDelayer {

    val counter = AtomicInteger(0)

    override val totalBandwidth = delegate.totalBandwidth

    override fun delay(remotePeer: SimPeerId, messageSize: Long): CompletableFuture<Unit> {
        val id = counter.getAndIncrement()
        logger("[$id] Started $messageSize")
        return delegate.delay(remotePeer, messageSize)
            .thenApply {
                logger("[$id] Completed $messageSize to/from $remotePeer")
            }
    }

    companion object {
        fun BandwidthDelayer.logging(logger: (String) -> Unit) = LoggingBandwidthDelayer(this, logger)
    }
}
