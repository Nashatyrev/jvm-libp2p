package io.libp2p.simulate

interface Network {

    val peers: List<SimPeer>

    val activeConnections: List<SimConnection>
        get() = peers.flatMap { it.connections }.distinct()
}

class ImmutableNetworkImpl(
    override val activeConnections: List<SimConnection>
) : Network {
    override val peers = activeConnections.map { it.dialer }.distinct()
}
