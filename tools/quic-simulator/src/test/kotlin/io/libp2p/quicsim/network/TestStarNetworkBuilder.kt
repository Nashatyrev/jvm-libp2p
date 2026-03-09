package io.libp2p.quicsim.network

import io.libp2p.quicsim.network.impl.BasicSimNetwork
import io.libp2p.quicsim.network.impl.BasicSimNetworkEngine
import java.time.Duration

class TestStarNetworkBuilder {
    private val nodes = linkedMapOf<String, SimNode>()
    private val links = mutableListOf<SimLink>()
    val router = SimNode("router-0")

    fun node(id: String): SimNode = nodes.getOrPut(id) { SimNode(id) }

    private fun link(
        from: SimNode,
        to: SimNode,
        latency: Duration,
        qdisc: SimQueueDiscipline,
        lossProbability: Double = 0.0
    ): TestStarNetworkBuilder {
        links += SimLink(
            from = from,
            to = to,
            latency = latency,
            lossProbability = lossProbability,
            qdisc = qdisc
        )
        return this
    }

    fun linkToRouter(
        node: SimNode,
        latency: Duration,
        qdiscFactory: () -> SimQueueDiscipline,
        lossProbability: Double = 0.0
    ): TestStarNetworkBuilder {
        link(node, router, latency, qdiscFactory(), lossProbability)
        link(router, node, latency, qdiscFactory(), lossProbability)
        return this
    }

    fun linkAllToRouter(
        latency: Duration,
        qdiscFactory: () -> SimQueueDiscipline,
        lossProbability: Double = 0.0
    ): TestStarNetworkBuilder {
        nodes.values.forEach { linkToRouter(it, latency, qdiscFactory, lossProbability)  }
        return this
    }

    fun build(): BasicSimNetwork = BasicSimNetwork(
        nodes = nodes.values.toList(),
        links = links.toList()
    )
}

data class ThreeNodeRouterFixture(
    val node1: SimNode,
    val node2: SimNode,
    val node3: SimNode,
    val router: SimNode,
    val network: BasicSimNetwork,
    val engine: BasicSimNetworkEngine
)

fun buildThreeNodeRouterFixture(
    node1Latency: Duration = Duration.ofMillis(10),
    node2Latency: Duration = Duration.ofMillis(20),
    node3Latency: Duration = Duration.ofMillis(30),
    qdiscFactory: () -> SimQueueDiscipline
): ThreeNodeRouterFixture {
    val builder = TestStarNetworkBuilder()
    val node1 = builder.node("node-1")
    val node2 = builder.node("node-2")
    val node3 = builder.node("node-3")

    builder.linkToRouter(node1, node1Latency, qdiscFactory)
    builder.linkToRouter(node2, node2Latency, qdiscFactory)
    builder.linkToRouter(node3, node3Latency, qdiscFactory)

    val network = builder.build()

    return ThreeNodeRouterFixture(
        node1 = node1,
        node2 = node2,
        node3 = node3,
        router = builder.router,
        network = network,
        engine = BasicSimNetworkEngine(network)
    )
}
