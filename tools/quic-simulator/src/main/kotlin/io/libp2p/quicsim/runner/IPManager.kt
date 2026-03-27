package io.libp2p.quicsim.runner

import io.libp2p.quicsim.sim.SimNodeId

interface IPManager {
    fun getIP(nodeId: SimNodeId): String

    object Default : IPManager {
        override fun getIP(nodeId: SimNodeId): String {
            check(nodeId < 256 * 256)
            val a = nodeId / 256
            val b = nodeId % 256
            return "10.0.$a.$b"
        }
    }
}