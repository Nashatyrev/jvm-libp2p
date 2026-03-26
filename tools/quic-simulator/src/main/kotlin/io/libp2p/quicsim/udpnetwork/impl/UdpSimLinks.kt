package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.UdpSimQueueDiscipline

class UdpSimLinks {

    val links = mutableListOf<UdpSimLink>()
    var defaultQDiscCtor: () -> UdpSimQueueDiscipline = { throw IllegalStateException("No default QDiscipline set") }

    fun withQDisc(qdiscCtor: () -> UdpSimQueueDiscipline) = also { defaultQDiscCtor = qdiscCtor }

    fun addUniDir(from: UdpSimNode, to: UdpSimNode) = addUniDir(from, to, defaultQDiscCtor())
    fun addUniDir(from: UdpSimNode, to: UdpSimNode, qdisc: UdpSimQueueDiscipline) = also {
        links += UdpSimLink(from, to, qdisc)
    }

    fun addBiDir(node1: UdpSimNode, node2: UdpSimNode) = addBiDir(node1, node2, defaultQDiscCtor)
    fun addBiDir(node1: UdpSimNode, node2: UdpSimNode, qdiscCtor: () -> UdpSimQueueDiscipline) =
        addUniDir(node1, node2, qdiscCtor())
            .addUniDir(node2, node1, qdiscCtor())

}
