package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork.Companion.nodesAndRouters
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class ShortestPathRouteResolver(
    network: UdpSimNetwork
) : RouteResolver {
    private val adjacency: Map<UdpSimNode, List<UdpSimNode>>
    private val nextHopBySource = ConcurrentHashMap<UdpSimNode, Map<UdpSimNode, UdpSimNode?>>()

    init {
        val outboundLinks = network.links.groupBy({ it.from }, { it.to })
        adjacency = network.nodesAndRouters.associateWith { node ->
            outboundLinks[node].orEmpty()
        }
    }

    override fun findNextHop(fromNode: UdpSimNode, destNode: UdpSimNode): UdpSimNode? {
        if (fromNode == destNode) {
            return null
        }
        val nextHops = nextHopBySource.computeIfAbsent(fromNode, ::shortestPathsFrom)
        return nextHops[destNode] ?: error("No route from $fromNode to $destNode")
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
