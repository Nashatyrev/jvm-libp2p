package io.libp2p.simulate

import java.util.IdentityHashMap

interface Network {

    val peers: List<SimPeer>

    val activeConnections: List<SimConnection>
        get() = peers.flatMap { it.connections }.distinct()

    fun getTopologyGraph(): TopologyGraph {
        val peerIdxMap = peers.withIndex().associateByTo(IdentityHashMap(), { it.value }, { it.index })
        return activeConnections
            .map { TopologyGraph.Edge(peerIdxMap[it.dialer]!!, peerIdxMap[it.listener]!!) }
            .let { TopologyGraph(it) }
    }
}

class ImmutableNetworkImpl(
    override val activeConnections: List<SimConnection>
) : Network {
    override val peers = activeConnections.map { it.dialer }.distinct()
}
