package io.libp2p.quicsim.network2

import io.libp2p.quicsim.network2.impl.BasicSimNetwork2
import io.libp2p.quicsim.network2.impl.SimLinks
import io.libp2p.quicsim.network2.impl.SimNetworkEngine2Impl
import java.time.Duration

class TestStarNetworkBuilder2 {
    private val nodes = linkedMapOf<String, SimNode>()
    private val links = SimLinks()

    val router = SimNode("router-0")

    fun node(id: String): SimNode = nodes.getOrPut(id) { SimNode(id) }

    fun linkToRouter(
        node: SimNode,
        latency: Duration,
        qdiscFactory: (Duration) -> SimQueueDiscipline2
    ): TestStarNetworkBuilder2 = also {
        links.addBiDir(node, router) { qdiscFactory(latency) }
    }

    fun linkAllToRouter(
        latency: Duration,
        qdiscFactory: (Duration) -> SimQueueDiscipline2
    ): TestStarNetworkBuilder2 = also {
        nodes.values.forEach { linkToRouter(it, latency, qdiscFactory) }
    }

    fun build(): BasicSimNetwork2 =
        BasicSimNetwork2(
            nodes = nodes.values.toList(),
            links = links.links.toList()
        )
}

data class ThreeNodeRouterFixture2(
    val node1: SimNode,
    val node2: SimNode,
    val node3: SimNode,
    val router: SimNode,
    val network: BasicSimNetwork2,
    val engine: SimNetworkEngine2Impl
)

fun buildThreeNodeRouterFixture2(
    node1Latency: Duration = Duration.ofMillis(10),
    node2Latency: Duration = Duration.ofMillis(20),
    node3Latency: Duration = Duration.ofMillis(30),
    qdiscFactory: (Duration) -> SimQueueDiscipline2
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
        engine = SimNetworkEngine2Impl(network)
    )
}
