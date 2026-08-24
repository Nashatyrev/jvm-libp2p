package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.ShortestPathRouteResolver
import io.libp2p.quicsim.udpnetwork.impl.UdpSimLinks
import kotlin.time.Duration

class TestNetworkBuilder {
    private val nodes = linkedMapOf<String, UdpSimNode>()
    private val routers = linkedMapOf<String, UdpSimNode>()
    private val links = UdpSimLinks()

    fun node(id: String): UdpSimNode = nodes.getOrPut(id) { UdpSimNode(id) }

    fun router(id: String): UdpSimNode = routers.getOrPut(id) { UdpSimNode(id) }

    fun linkBiDir(
        left: UdpSimNode,
        right: UdpSimNode,
        latency: Duration,
        qdiscFactory: TestQDiscFactory
    ): TestNetworkBuilder = also {
        links
            .addUniDir(left, right, qdiscFactory(latency, left in nodes.values))
            .addUniDir(right, left, qdiscFactory(latency, right in nodes.values))
    }

    fun build(): BasicUdpSimNetwork =
        BasicUdpSimNetwork(
            nodes = nodes.values.toList(),
            links = links.links.toList()
        )

    fun routeResolver(): RouteResolver =
        ShortestPathRouteResolver(build())
}
