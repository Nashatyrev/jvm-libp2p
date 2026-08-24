package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import java.util.ArrayDeque

class ShortestPathRouteResolver(
    network: UdpSimNetwork
) : RouteResolver {
    private val adjacency: Map<UdpSimNode, List<UdpSimNode>> =
        network.links.groupBy { it.from }.mapValues { entry -> entry.value.map { it.to } }
    private val nextHopByRoute: Map<Pair<UdpSimNode, UdpSimNode>, UdpSimNode?> =
        adjacency.keys.associateWith { from -> shortestPathsFrom(from) }
            .flatMap { (from, nextHops) ->
                nextHops.map { (destination, nextHop) -> (from to destination) to nextHop }
            }
            .toMap()

    override fun findNextHop(fromNode: UdpSimNode, destNode: UdpSimNode): UdpSimNode? {
        if (fromNode == destNode) {
            return null
        }
        return nextHopByRoute[fromNode to destNode] ?: error("No route from $fromNode to $destNode")
    }

    private fun shortestPathsFrom(fromNode: UdpSimNode): Map<UdpSimNode, UdpSimNode?> {
        val visited = mutableSetOf(fromNode)
        val queue = ArrayDeque<Pair<UdpSimNode, UdpSimNode>>()
        val nextHops = linkedMapOf<UdpSimNode, UdpSimNode?>(fromNode to null)

        adjacency[fromNode].orEmpty().forEach { next ->
            visited += next
            nextHops[next] = next
            queue += next to next
        }

        while (queue.isNotEmpty()) {
            val (node, firstHop) = queue.removeFirst()
            adjacency[node].orEmpty().forEach { next ->
                if (next in visited) {
                    return@forEach
                }
                visited += next
                nextHops[next] = firstHop
                queue += next to firstHop
            }
        }

        return nextHops
    }
}
