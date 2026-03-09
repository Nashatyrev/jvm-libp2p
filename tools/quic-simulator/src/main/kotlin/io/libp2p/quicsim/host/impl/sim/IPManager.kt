package io.libp2p.quicsim.host.impl.sim

import io.libp2p.quicsim.host.SimNodeId

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

