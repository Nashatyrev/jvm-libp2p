package io.libp2p.simulate

import io.libp2p.core.PeerId
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

interface SimPeer {

    val name: String
    val peerId: PeerId
    val connections: List<SimConnection>

    var inboundBandwidth: BandwidthDelayer
    var outboundBandwidth: BandwidthDelayer

    fun start() = CompletableFuture.completedFuture(Unit)

    fun connect(other: SimPeer): CompletableFuture<SimConnection>

    fun stop(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
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

    abstract fun connectImpl(other: SimPeer): CompletableFuture<SimConnection>

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AbstractSimPeer
        return name == other.name
    }

    override fun hashCode(): Int = name.hashCode()

    companion object {
        val counter = AtomicInteger()
    }
}
