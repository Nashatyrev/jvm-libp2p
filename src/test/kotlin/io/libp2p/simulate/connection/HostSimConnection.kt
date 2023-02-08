package io.libp2p.simulate.connection

import io.libp2p.core.Connection
import io.libp2p.simulate.ConnectionStat
import io.libp2p.simulate.MessageDelayer
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.SimStream

class HostSimConnection(
    override val dialer: HostSimPeer,
    override val listener: HostSimPeer,
    val conn: Connection
) : SimConnection {
    override val closed = conn.closeFuture()

    override fun close() {
        conn.close()
    }

    override val streams: List<SimStream>
        get() = TODO("Not yet implemented")
    override val dialerStat: ConnectionStat
        get() = TODO("not implemented")
    override val listenerStat: ConnectionStat
        get() = TODO("not implemented")
    override var connectionLatency: MessageDelayer
        get() = TODO("Not yet implemented")
        set(value) {}
}