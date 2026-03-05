package io.libp2p.quicsim.network

import io.libp2p.quicsim.network.impl.BasicSimNetwork
import io.libp2p.quicsim.network.impl.BasicSimNetworkEngine
import java.time.Duration

class TestNetworkBuilder {
    private val nodes = linkedMapOf<String, SimNode>()
    private val links = mutableListOf<SimLink>()

    fun node(id: String): SimNode = nodes.getOrPut(id) { SimNode(id) }

    fun link(
        from: SimNode,
        to: SimNode,
        latency: Duration,
        qdisc: SimQueueDiscipline,
        lossProbability: Double = 0.0
    ): TestNetworkBuilder {
        links += SimLink(
            from = from,
            to = to,
            latency = latency,
            lossProbability = lossProbability,
            qdisc = qdisc
        )
        return this
    }

    fun bidirectional(
        a: SimNode,
        b: SimNode,
        latency: Duration,
        qdiscFactory: () -> SimQueueDiscipline,
        lossProbability: Double = 0.0
    ): TestNetworkBuilder {
        link(a, b, latency, qdiscFactory(), lossProbability)
        link(b, a, latency, qdiscFactory(), lossProbability)
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
    val builder = TestNetworkBuilder()
    val node1 = builder.node("node-1")
    val node2 = builder.node("node-2")
    val node3 = builder.node("node-3")
    val router = builder.node("router-1")

    builder
        .bidirectional(node1, router, node1Latency, qdiscFactory)
        .bidirectional(node2, router, node2Latency, qdiscFactory)
        .bidirectional(node3, router, node3Latency, qdiscFactory)

    val network = builder.build()
    return ThreeNodeRouterFixture(
        node1 = node1,
        node2 = node2,
        node3 = node3,
        router = router,
        network = network,
        engine = BasicSimNetworkEngine(network)
    )
}
