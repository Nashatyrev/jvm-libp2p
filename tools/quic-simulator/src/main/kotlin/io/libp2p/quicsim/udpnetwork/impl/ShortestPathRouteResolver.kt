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
