package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.UdpSimLinks
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl
import java.time.Duration

class TestStarNetworkBuilder2 {
    private val nodes = linkedMapOf<String, UdpSimNode>()
    private val links = UdpSimLinks()

    val router = UdpSimNode("router-0")

    fun node(id: String): UdpSimNode = nodes.getOrPut(id) { UdpSimNode(id) }

    fun linkToRouter(
        node: UdpSimNode,
        latency: Duration,
        qdiscFactory: (Duration) -> UdpSimQueueDiscipline
    ): TestStarNetworkBuilder2 = also {
        links.addBiDir(node, router) { qdiscFactory(latency) }
    }

    fun linkAllToRouter(
        latency: Duration,
        qdiscFactory: (Duration) -> UdpSimQueueDiscipline
    ): TestStarNetworkBuilder2 = also {
        nodes.values.forEach { linkToRouter(it, latency, qdiscFactory) }
    }

    fun build(): BasicUdpSimNetwork =
        BasicUdpSimNetwork(
            nodes = nodes.values.toList(),
            links = links.links.toList()
        )
}
