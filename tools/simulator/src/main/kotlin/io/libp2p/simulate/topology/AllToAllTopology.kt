package io.libp2p.simulate.topology

import io.libp2p.simulate.*
import java.util.*

class AllToAllTopology : Topology {

    override var random: Random
        get() = TODO("Not yet implemented")
        set(value) {}

    override fun connect(peers: List<SimPeer>): Network {
        val conns = mutableListOf<SimConnection>()
        for (i in peers.indices) {
            for (j in (i+1) until peers.size) {
                conns += peers[i].connect(peers[j]).join()
            }
        }
        return ImmutableNetworkImpl(conns)
    }
}