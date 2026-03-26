package io.libp2p.quicsim.network2.impl

import io.libp2p.quicsim.network2.RouteResolver
import io.libp2p.quicsim.network2.SimNetwork2
import io.libp2p.quicsim.network2.SimNode

class BasicStarRouteResolver(
    val network: SimNetwork2
) : RouteResolver {

    private val allNodes: Set<SimNode>
        get() = network.nodes.toSet() + network.links.flatMap { listOf(it.from, it.to) }

    /** Nodes which are not routers */
    private val endpointsByFrom
        get() =
            allNodes
                .groupingBy { node -> node }
                .eachCount()
                .keys
                .associateWith { node -> network.links.count { it.from == node } }
                .filter { it.value == 1 }
                .map { it.key }
                .toSet()
                .also { byFrom ->
                    val byTo =
                        allNodes
                            .associateWith { node -> network.links.count { it.to == node } }
                            .filter { it.value == 1 }
                            .map { it.key }
                            .toSet()
                    check(byFrom == byTo) { "Not of star topology" }
                }

    private val routers
        get() = (allNodes - endpointsByFrom)
            .also {
                check(it.size == 1) { "Not of star topology" }
            }
    private val router: SimNode
        get() = routers.first()

    override fun findNextHop(
        fromNode: SimNode,
        destNode: SimNode
    ): SimNode? =
        when (fromNode) {
            destNode -> null
            router -> destNode
            else -> router
        }
}
