package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.TestUdpSimQueue
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNode

class UdpSimLinks {

    val links = mutableListOf<UdpSimLink>()
    var defaultQDiscCtor: () -> TestUdpSimQueue = { throw IllegalStateException("No default QDiscipline set") }

    fun withQDisc(qdiscCtor: () -> TestUdpSimQueue) = also { defaultQDiscCtor = qdiscCtor }

    fun addUniDir(from: UdpSimNode, to: UdpSimNode) = addUniDir(from, to, defaultQDiscCtor())

    fun addUniDir(from: UdpSimNode, to: UdpSimNode, qdisc: TestUdpSimQueue) = also {
        links += UdpSimLink(
            from = from,
            to = to,
            bandwidthQueue = qdisc.bandwidthQueue,
            latencyQueue = qdisc.latencyQueue,
            qdisc = qdisc
        )
    }

    fun addBiDir(node1: UdpSimNode, node2: UdpSimNode) = addBiDir(node1, node2, defaultQDiscCtor)

    fun addBiDir(node1: UdpSimNode, node2: UdpSimNode, qdiscCtor: () -> TestUdpSimQueue) =
        addUniDir(node1, node2, qdiscCtor())
            .addUniDir(node2, node1, qdiscCtor())
}
