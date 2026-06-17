package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.UdpSimLinks
import kotlin.time.Duration

typealias TestQDiscFactory = (latency: Duration, isFromEndpoint: Boolean) -> TestUdpSimQueue

class TestStarNetworkBuilder2 {
    private val nodes = linkedMapOf<String, UdpSimNode>()
    private val links = UdpSimLinks()

    val router = UdpSimNode("router-0")

    fun node(id: String): UdpSimNode = nodes.getOrPut(id) { UdpSimNode(id) }

    fun linkToRouter(
        node: UdpSimNode,
        latency: Duration,
        qdiscFactory: TestQDiscFactory
    ): TestStarNetworkBuilder2 = also {
        links
            .addUniDir(node, router, qdiscFactory(latency, true))
            .addUniDir(router, node, qdiscFactory(latency, false))
    }

    fun linkAllToRouter(
        latency: Duration,
        qdiscFactory: TestQDiscFactory
    ): TestStarNetworkBuilder2 = also {
        nodes.values.forEach { linkToRouter(it, latency, qdiscFactory) }
    }

    fun build(): BasicUdpSimNetwork =
        BasicUdpSimNetwork(
            nodes = nodes.values.toList(),
            links = links.links.toList()
        )
}
