package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.deliver
import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.udpSimDestinationNodeId
import io.libp2p.quicsim.udpnetwork.udpSimSourceNodeId
import io.netty.channel.socket.DatagramPacket
import kotlin.collections.plusAssign
import kotlin.time.Duration

class UdpSimNetworkEngineImpl(
    override val network: UdpSimNetwork,
    private val routeResolver: RouteResolver = BasicStarRouteResolver(network)
) : UdpSimNetworkEngine {

    val idToNodeMap = network.nodes.associateBy { it.id }

    private val aggregateControllable = AggregateControllable(network.links.map { it.qdisc })
    private val linksMap = network.links.associateBy { it.from to it.to }
    private val outboundPackets = mutableListOf<DatagramPacket>()
    private var emissionPrepared = false

    private fun findNextLink(fromLink: UdpSimLink?, packet: DatagramPacket): UdpSimLink? {
        val srcHopNode = fromLink?.to ?: idToNodeMap[packet.udpSimSourceNodeId()]!!
        val nextHopNode = routeResolver.findNextHop(
            srcHopNode, idToNodeMap[packet.udpSimDestinationNodeId()]!!
        )
        return if (nextHopNode != null) {
            linksMap[srcHopNode to nextHopNode]!!
        } else {
            null
        }
    }

    override fun receivePackets(packets: List<DatagramPacket>) {
        outboundPackets += processPackets(packets)
        emissionPrepared = true
    }

    override fun emitPackets(): List<DatagramPacket> {
        if (!emissionPrepared) {
            outboundPackets += processPackets(emptyList())
        }
        emissionPrepared = false
        val emitted = outboundPackets.toList()
        outboundPackets.clear()
        return emitted
    }

    private fun processPackets(inboundData: List<DatagramPacket>): List<DatagramPacket> {
        val packetsForLink =
            network.links.associateWith { mutableListOf<DatagramPacket>() }
                .toMutableMap()

        inboundData
            .forEach { inboundPacket ->
                val link = findNextLink(null, inboundPacket)
                    ?: throw IllegalStateException("Direct links from endpoint to endpoint are not supported in this implementation")
                packetsForLink[link]!! += inboundPacket
            }

        val deliveredPackets = mutableListOf<DatagramPacket>()

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

    override fun advance(advanceDuration: Duration) {
        aggregateControllable.advance(advanceDuration)
    }

    override fun executePending() {
        aggregateControllable.executePending()
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
