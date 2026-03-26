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

data class ThreeNodeRouterFixture2(
    val node1: UdpSimNode,
    val node2: UdpSimNode,
    val node3: UdpSimNode,
    val router: UdpSimNode,
    val network: BasicUdpSimNetwork,
    val engine: UdpSimNetworkEngineImpl
)

fun buildThreeNodeRouterFixture2(
    node1Latency: Duration = Duration.ofMillis(10),
    node2Latency: Duration = Duration.ofMillis(20),
    node3Latency: Duration = Duration.ofMillis(30),
    qdiscFactory: (Duration) -> UdpSimQueueDiscipline
): ThreeNodeRouterFixture2 {
    val builder = TestStarNetworkBuilder2()
    val node1 = builder.node("node-1")
    val node2 = builder.node("node-2")
    val node3 = builder.node("node-3")

    builder.linkToRouter(node1, node1Latency, qdiscFactory)
    builder.linkToRouter(node2, node2Latency, qdiscFactory)
    builder.linkToRouter(node3, node3Latency, qdiscFactory)

    val network = builder.build()

    return ThreeNodeRouterFixture2(
        node1 = node1,
        node2 = node2,
        node3 = node3,
        router = builder.router,
        network = network,
        engine = UdpSimNetworkEngineImpl(network)
    )
}
