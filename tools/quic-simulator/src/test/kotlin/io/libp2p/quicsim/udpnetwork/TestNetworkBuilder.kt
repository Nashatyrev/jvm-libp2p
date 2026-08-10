package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.UdpSimLinks
import java.util.ArrayDeque
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

    private class ShortestPathRouteResolver(
        network: UdpSimNetwork
    ) : RouteResolver {
        private val adjacency = network.links
            .groupBy { it.from }
            .mapValues { entry -> entry.value.map { it.to } }

        override fun findNextHop(fromNode: UdpSimNode, destNode: UdpSimNode): UdpSimNode? {
            if (fromNode == destNode) {
                return null
            }
            val visited = mutableSetOf(fromNode)
            val queue = ArrayDeque<List<UdpSimNode>>()
            queue += listOf(fromNode)
            while (queue.isNotEmpty()) {
                val path = queue.removeFirst()
                adjacency[path.last()].orEmpty().forEach { next ->
                    if (next in visited) {
                        return@forEach
                    }
                    val nextPath = path + next
                    if (next == destNode) {
                        return nextPath[1]
                    }
                    visited += next
                    queue += nextPath
                }
            }
            error("No route from $fromNode to $destNode")
        }
    }
}
