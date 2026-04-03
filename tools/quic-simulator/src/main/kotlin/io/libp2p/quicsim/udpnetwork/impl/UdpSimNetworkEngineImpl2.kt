package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.ValueSortedMap
import io.libp2p.quicsim.core.schedule.PacketProcessorB
import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import kotlin.collections.plusAssign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class UdpSimNetworkEngineImpl2(
    override val network: UdpSimNetwork,
    private val routeResolver: RouteResolver = BasicStarRouteResolver(network)
) : UdpSimNetworkEngine {

    private var cumulativeAdvanceMutable: Duration = ZERO
    val cumulativeAdvance get() = cumulativeAdvanceMutable

    val idToNodeMap = network.nodes.associateBy { it.id }
    data class WrappedLink(
        val link: UdpSimLink,
        val wrapper: PacketProcessorB<UdpSimPacket> = PacketProcessorB(link.qdisc)
    )
    private val linksMap = ValueSortedMap(
        network.links
            .map { WrappedLink(it) }
            .associateBy { it.link.from to it.link.to }
    ) { value ->
        value.wrapper.nextTaskPoint ?: Duration.INFINITE
    }

    private fun findNextLink(fromLink: UdpSimLink?, packet: UdpSimPacket): Pair<UdpSimNode, UdpSimNode>? {
        val srcHopNode = fromLink?.to ?: idToNodeMap[packet.srcNodeId]!!
        val nextHopNode = routeResolver.findNextHop(
            srcHopNode, idToNodeMap[packet.dstNodeId]!!
        )
        return if (nextHopNode != null) {
            srcHopNode to nextHopNode
        } else {
            null
        }
    }

    private fun deliverInbound(packet: UdpSimPacket, fromLink: UdpSimLink?, deliveredPackets: MutableList<UdpSimPacket>) {
        val linkKey = findNextLink(fromLink, packet)
        if (linkKey == null) {
            deliveredPackets += packet
        } else {
            linksMap.updateByKey(linkKey) {
                it.wrapper.advanceTillAndExecute(cumulativeAdvance)
                it.wrapper.deliverInbound(listOf(packet))
            }
        }
    }

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
        val deliveredPackets: MutableList<UdpSimPacket> = mutableListOf()

        inboundData
            .forEach {
                deliverInbound(it, null, deliveredPackets)
            }

        @Suppress("ControlFlowWithEmptyBody")
        while (
            linksMap.updateFirst { link ->
                if ((link.wrapper.nextTaskPoint ?: Duration.INFINITE) > cumulativeAdvanceMutable) {
                    false
                } else {
                    link.wrapper.advanceTillAndExecute(cumulativeAdvanceMutable)
                    val outbound = link.wrapper.deliverOutbound()
                    outbound
                        .forEach {
                            deliverInbound(it, link.link, deliveredPackets)
                        }
                    true
                }
            }
        ) {}
        return deliveredPackets
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        cumulativeAdvanceMutable += advanceDuration

    }

    override fun nextTaskDuration(): Duration? =
        linksMap.getFirst().wrapper.nextTaskPoint?.let { it - cumulativeAdvanceMutable }
}
