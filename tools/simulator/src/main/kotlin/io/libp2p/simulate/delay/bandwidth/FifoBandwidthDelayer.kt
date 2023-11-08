package io.libp2p.simulate.delay.bandwidth

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.SimPeerId
import java.util.concurrent.CompletableFuture

/**
 * Refers to networking queueing discipline which doesn't fairly share the bandwidth across connections
 * and a small message on one connection would be transmitted after any other larger messages queued
 * for another connection
 */
abstract class FifoBandwidthDelayer(
    override val totalBandwidth: Bandwidth
) : BandwidthDelayer {

    override fun delay(remotePeer: SimPeerId, messageSize: Long): CompletableFuture<Unit> =
        delay(messageSize)

    abstract fun delay(messageSize: Long): CompletableFuture<Unit>
}