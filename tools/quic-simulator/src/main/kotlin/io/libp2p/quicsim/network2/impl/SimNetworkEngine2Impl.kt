package io.libp2p.quicsim.network2.impl

import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.network2.RouteResolver
import io.libp2p.quicsim.network2.SimLink2
import io.libp2p.quicsim.network2.SimNetwork2
import io.libp2p.quicsim.network2.SimNetworkEngine2
import io.libp2p.quicsim.network2.SimPacket
import kotlin.collections.plusAssign
import kotlin.time.Duration

class SimNetworkEngine2Impl(
    override val network: SimNetwork2,
    private val routeResolver: RouteResolver = BasicStarRouteResolver(network)
) : SimNetworkEngine2 {

    val idToNodeMap = network.nodes.associateBy { it.id }

    private val aggregateControllable = AggregateControllable(network.links.map { it.qdisc })
    private val linksMap = network.links.associateBy { it.from to it.to }

    private fun findNextLink(fromLink: SimLink2?, packet: SimPacket): SimLink2? {
        val srcHopNode = fromLink?.to ?: idToNodeMap[packet.srcNodeId]!!
        val nextHopNode = routeResolver.findNextHop(
            srcHopNode, idToNodeMap[packet.dstNodeId]!!
        )
        return if (nextHopNode != null) {
            linksMap[srcHopNode to nextHopNode]!!
        } else {
            null
        }
    }

    override fun deliver(inboundData: List<SimPacket>): List<SimPacket> {
        val packetsForLink =
            network.links.associateWith { mutableListOf<SimPacket>() }
                .toMutableMap()

        inboundData
            .forEach { inboundPacket ->
                val link = findNextLink(null, inboundPacket)
                    ?: throw IllegalStateException("Direct links from endpoint to endpoint are not supported in this implementation")
                packetsForLink[link]!! += inboundPacket
            }

        val deliveredPackets = mutableListOf<SimPacket>()

        while (packetsForLink.isNotEmpty()) {
            val (link, packets) = packetsForLink.removeFirst()
            val linkOutPackets = link.qdisc.deliver(packets)
            linkOutPackets.forEach { packet ->
                val nextLink = findNextLink(link, packet)
                if (nextLink != null) {
                    packetsForLink.computeIfAbsent(nextLink) { mutableListOf() } += packet
                } else {
                    deliveredPackets += packet
                }
            }
        }

        return deliveredPackets
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        aggregateControllable.advanceAndExecuteAll(advanceDuration)
    }

    override fun nextTaskDuration(): Duration? =
        aggregateControllable.nextTaskDuration()

    private companion object {
        fun <K, V> MutableMap<K, V>.removeFirst(): Map.Entry<K, V> {
            val iterator = this.entries.iterator()
            val ret = iterator.next()
            iterator.remove()
            return ret
        }
    }

}
