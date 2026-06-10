package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import kotlin.collections.plusAssign
import kotlin.time.Duration

class ParallelUdpSimNetworkEngine(
    override val network: UdpSimNetwork,
    private val routeResolver: RouteResolver = BasicStarRouteResolver(network)
) : UdpSimNetworkEngine {

    private val endpointNodes = network.nodes.toSet()
    private val idToNodeMap = network.nodes.associateBy { it.id }
    private val linksMap = network.links.associateBy { it.from to it.to }

    private fun findNextLink(fromLink: UdpSimLink?, packet: UdpSimPacket): UdpSimLink? {
        val srcHopNode = fromLink?.to ?: idToNodeMap.getValue(packet.srcNodeId)
        val nextHopNode = routeResolver.findNextHop(
            srcHopNode,
            idToNodeMap.getValue(packet.dstNodeId)
        )
        return nextHopNode?.let { linksMap.getValue(srcHopNode to it) }
    }

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
        val deliveredPackets = mutableListOf<UdpSimPacket>()
        inboundData.forEach { packet ->
            val link = findNextLink(null, packet)
                ?: throw IllegalStateException("Direct links from endpoint to endpoint are not supported")
            deliverToLinkStart(link, packet, deliveredPackets)
        }
        drainReady(deliveredPackets)
        return deliveredPackets
    }

    override fun advance(advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
        network.links.forEach { link ->
            link.latencyQueue.advance(advanceDuration)
            link.bandwidthQueue.advance(advanceDuration)
        }
    }

    override fun executePending() {
        network.links.forEach { link ->
            link.latencyQueue.executePending()
            link.bandwidthQueue.executePending()
        }
        drainReady(mutableListOf())
    }

    override fun nextTaskDuration(): Duration? =
        network.links.flatMap { link ->
            val latencyTask =
                if (link.from in endpointNodes) {
                    link.latencyQueue.nextTaskDuration()
                } else {
                    null
                }
            listOfNotNull(latencyTask, link.bandwidthQueue.nextTaskDuration())
        }
            .minOrNull()

    private fun drainReady(deliveredPackets: MutableList<UdpSimPacket>) {
        do {
            var moved = false
            network.links.forEach { link ->
                val latencyOut =
                    if (link.from in endpointNodes) {
                        link.latencyQueue.deliver(emptyList())
                    } else {
                        emptyList()
                    }
                if (latencyOut.isNotEmpty()) {
                    moved = true
                    latencyOut.forEach { packet ->
                        deliverFromLatency(link, packet, deliveredPackets)
                    }
                }

                val bandwidthOut = link.bandwidthQueue.deliver(emptyList())
                if (bandwidthOut.isNotEmpty()) {
                    moved = true
                    bandwidthOut.forEach { packet ->
                        deliverFromBandwidth(link, packet, deliveredPackets)
                    }
                }
            }
        } while (moved)
    }

    private fun deliverToLinkStart(
        link: UdpSimLink,
        packet: UdpSimPacket,
        deliveredPackets: MutableList<UdpSimPacket>
    ) {
        val ready = if (link.from in endpointNodes) {
            link.latencyQueue.deliver(listOf(packet))
        } else {
            link.bandwidthQueue.deliver(listOf(packet))
        }
        ready.forEach { readyPacket ->
            if (link.from in endpointNodes) {
                deliverFromLatency(link, readyPacket, deliveredPackets)
            } else {
                deliverFromBandwidth(link, readyPacket, deliveredPackets)
            }
        }
    }

    private fun deliverFromLatency(
        link: UdpSimLink,
        packet: UdpSimPacket,
        deliveredPackets: MutableList<UdpSimPacket>
    ) {
        if (link.to in endpointNodes) {
            deliveredPackets += packet
        } else {
            link.bandwidthQueue.deliver(listOf(packet))
                .forEach { deliverFromBandwidth(link, it, deliveredPackets) }
        }
    }

    private fun deliverFromBandwidth(
        link: UdpSimLink,
        packet: UdpSimPacket,
        deliveredPackets: MutableList<UdpSimPacket>
    ) {
        if (link.to in endpointNodes) {
            link.latencyQueue.deliver(listOf(packet))
        } else {
            val nextLink = findNextLink(link, packet)
            if (nextLink == null) {
                deliveredPackets += packet
            } else {
                deliverToLinkStart(nextLink, packet, deliveredPackets)
            }
        }
    }
}
