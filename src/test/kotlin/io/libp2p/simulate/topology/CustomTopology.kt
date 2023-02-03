package io.libp2p.simulate.topology

import io.libp2p.simulate.ImmutableNetworkImpl
import io.libp2p.simulate.Network
import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.Topology
import java.util.*

class CustomTopology(val connections: List<Pair<Int, Int>>) : Topology {
    override var random: Random
        get() = TODO("Not yet implemented")
        set(value) {}

    override fun connect(peers: List<SimPeer>): Network {
        return connections.map {
            peers[it.first].connect(peers[it.second]).join()
        }.let { ImmutableNetworkImpl(it) }
    }
}