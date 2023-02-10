package io.libp2p.simulate

import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stats.WritableStats
import java.util.concurrent.CompletableFuture

interface SimConnection {

    val dialer: SimPeer
    val listener: SimPeer

    val streams: List<SimStream>

    val closed: CompletableFuture<Unit>

    var connectionLatency: MessageDelayer

    fun close()

    fun isClosed() = closed.isDone

}
