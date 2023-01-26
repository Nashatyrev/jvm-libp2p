package io.libp2p.simulate.wire

import io.libp2p.core.Connection
import io.libp2p.simulate.ConnectionStat
import io.libp2p.simulate.MessageDelayer
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimPeer

class WireSimConnection(
    override val dialer: SimPeer,
    override val listener: SimPeer,
    val conn: Connection
) : SimConnection {

    override val closed = conn.closeFuture()

    override fun close() {
        conn.close()
    }

    override val dialerStat: ConnectionStat
        get() = TODO("not implemented")
    override val listenerStat: ConnectionStat
        get() = TODO("not implemented")
    override var connectionLatency: MessageDelayer
        get() = TODO("Not yet implemented")
        set(value) {}
}