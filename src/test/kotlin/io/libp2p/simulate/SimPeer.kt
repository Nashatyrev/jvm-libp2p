package io.libp2p.simulate

import io.libp2p.core.PeerId
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

interface SimPeer {

    val name: String
    val peerId: PeerId
    val connections: List<SimConnection>

    // TODO
    var inboundBandwidth: BandwidthDelayer
    var outboundBandwidth: BandwidthDelayer

    fun start() = CompletableFuture.completedFuture(Unit)

    fun connect(other: SimPeer): CompletableFuture<SimConnection>

    fun setBandwidth(bandwidth: RandomValue)

    fun stop(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    fun getConnectedPeers() =
        connections.flatMap { listOf(it.dialer, it.listener) }.distinct() - this
}

abstract class AbstractSimPeer : SimPeer {

    override val name = counter.getAndIncrement().toString()

    override val connections: MutableList<SimConnection> = Collections.synchronizedList(ArrayList())

    override fun connect(other: SimPeer): CompletableFuture<SimConnection> {
        return connectImpl(other).thenApply { conn ->
            val otherAbs = other as? AbstractSimPeer
            connections += conn
            otherAbs?.connections?.add(conn)
            conn.closed.thenAccept {
                connections -= conn
                otherAbs?.connections?.remove(conn)
            }
            conn
        }
    }

    override fun setBandwidth(bandwidth: RandomValue): Unit = TODO()

    abstract fun connectImpl(other: SimPeer): CompletableFuture<SimConnection>

    companion object {
        val counter = AtomicInteger()
    }
}