package io.libp2p.quicsim.network2.impl

import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network2.RouteResolver
import io.libp2p.quicsim.network2.SimNetwork2

class BasicStarRouteResolver(
    val network: SimNetwork2
) : RouteResolver {

    /** Nodes which are not routers */
    private val endpointsByFrom
        get() =
            network.links
                .groupingBy { it.from }
                .eachCount()
                .filter { it.value == 1 }
                .map { it.key }
                .toSet()
                .also { byFrom ->
                    val byTo =
                        network.links
                            .groupingBy { it.to }
                            .eachCount()
                            .filter { it.value == 1 }
                            .map { it.key }
                            .toSet()
                    check(byFrom == byTo) { "Not of star topology" }
                }

    private val routers
        get() = (network.nodes - endpointsByFrom)
            .also {
                check(it.size == 1) { "Not of star topology" }
            }
    private val router = routers.first()

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