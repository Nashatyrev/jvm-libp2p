package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.ValueSortedMap
import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import kotlin.collections.plusAssign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class ParallelUdpSimNetworkEngine(
    override val network: UdpSimNetwork,
    private val routeResolver: RouteResolver = BasicStarRouteResolver(network),
    private val drainEndpointBoundLatency: Boolean = true
) : UdpSimNetworkEngine {

    private val endpointNodes = network.nodes.toSet()
    private val idToNodeMap = network.nodes.associateBy { it.id }
    private val linksMap = network.links.associateBy { it.from to it.to }
    private var cumulativeAdvance: Duration = ZERO
    val currentTime get() = cumulativeAdvance
    private var nodeFacingDeliveryFloor: Duration = ZERO
    private val deliveredReady = mutableListOf<UdpSimPacket>()
    private val latencyLinksToDrainKeys = network.links
        .filter { shouldDrainLatency(it) }
        .map { it.from to it.to }

    private val latencyLinksToDrain = ValueSortedMap(
        latencyLinksToDrainKeys.associateWith { linksMap.getValue(it) }
    ) { link ->
        link.latencyQueue.nextTaskDuration()
            ?.let { link.latencyQueue.cumulativeAdvance + it }
            ?: Duration.INFINITE
    }

    private val bandwidthLinks = ValueSortedMap(
        network.links.associateBy { it.from to it.to }
    ) { link ->
        link.bandwidthQueue.nextTaskDuration()
            ?.let { link.bandwidthQueue.cumulativeAdvance + it }
            ?: Duration.INFINITE
    }

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
        deliveredPackets += deliveredReady
        deliveredReady.clear()
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
        cumulativeAdvance += advanceDuration
    }

    fun advanceAndExecuteUntil(advanceDuration: Duration) {
        val deliveryFloor = cumulativeAdvance + advanceDuration
        val previousDeliveryFloor = nodeFacingDeliveryFloor
        nodeFacingDeliveryFloor = deliveryFloor
        try {
            refreshExternallyUpdatedLatencyQueues()
            var timeLeft = advanceDuration
            while (timeLeft > ZERO) {
                val nextAdvance = minOf(timeLeft, nextTaskDuration() ?: timeLeft)
                advance(nextAdvance)
                executePending()
                timeLeft -= nextAdvance
            }
        } finally {
            advanceAllLatencyQueuesToCurrent()
            nodeFacingDeliveryFloor = previousDeliveryFloor.coerceAtLeast(cumulativeAdvance)
        }
    }

    override fun executePending() {
        drainReady(deliveredReady)
    }

    override fun nextTaskDuration(): Duration? =
        listOf(
            latencyLinksToDrain.getFirstOrNull()?.let { latencyTaskDuration(it) },
            bandwidthLinks.getFirstOrNull()?.let { bandwidthTaskDuration(it) }
        ).filterNotNull().minOrNull()

    private fun drainReady(deliveredPackets: MutableList<UdpSimPacket>) {
        do {
            val movedLatency = drainFirstReadyOutboundLatency(deliveredPackets)
            val movedBandwidth = drainFirstReadyBandwidth(deliveredPackets)
        } while (movedLatency || movedBandwidth)
    }

    private fun drainFirstReadyOutboundLatency(deliveredPackets: MutableList<UdpSimPacket>): Boolean =
        latencyLinksToDrain.updateFirstOrNull { link ->
            if (latencyTaskDuration(link) != ZERO) {
                false
            } else {
                advanceLatencyQueueToCurrent(link)
                link.latencyQueue.deliver(emptyList())
                    .forEach { packet ->
                        deliverFromLatency(link, packet, deliveredPackets)
                    }
                true
            }
        } ?: false

    private fun drainFirstReadyBandwidth(deliveredPackets: MutableList<UdpSimPacket>): Boolean =
        bandwidthLinks.updateFirstOrNull { link ->
            if (bandwidthTaskDuration(link) != ZERO) {
                false
            } else {
                advanceBandwidthQueueToCurrent(link)
                link.bandwidthQueue.deliver(emptyList())
                    .forEach { packet ->
                        deliverFromBandwidth(link, packet, deliveredPackets)
                    }
                true
            }
        } ?: false

    private fun deliverToLinkStart(
        link: UdpSimLink,
        packet: UdpSimPacket,
        deliveredPackets: MutableList<UdpSimPacket>
    ) {
        if (link.from in endpointNodes) {
            latencyLinksToDrain.updateByKey(link.from to link.to) {
                advanceLatencyQueueToCurrent(it)
                it.latencyQueue.deliver(listOf(packet))
                    .forEach { readyPacket ->
                        deliverFromLatency(it, readyPacket, deliveredPackets)
                    }
            }
        } else {
            bandwidthLinks.updateByKey(link.from to link.to) {
                advanceBandwidthQueueToCurrent(it)
                it.bandwidthQueue.deliver(listOf(packet))
                    .forEach { readyPacket ->
                        deliverFromBandwidth(it, readyPacket, deliveredPackets)
                    }
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
            bandwidthLinks.updateByKey(link.from to link.to) {
                advanceBandwidthQueueToCurrent(it)
                it.bandwidthQueue.deliver(listOf(packet))
                    .forEach { readyPacket ->
                        deliverFromBandwidth(it, readyPacket, deliveredPackets)
                    }
            }
        }
    }

    private fun deliverFromBandwidth(
        link: UdpSimLink,
        packet: UdpSimPacket,
        deliveredPackets: MutableList<UdpSimPacket>
    ) {
        if (link.to in endpointNodes) {
            if (drainEndpointBoundLatency) {
                latencyLinksToDrain.updateByKey(link.from to link.to) {
                    advanceLatencyQueueToCurrent(it)
                    it.latencyQueue.enqueueInboundWithDeliveryFloor(listOf(packet), nodeFacingDeliveryFloor)
                }
            } else {
                advanceLatencyQueueToCurrent(link)
                link.latencyQueue.enqueueInboundWithDeliveryFloor(listOf(packet), nodeFacingDeliveryFloor)
            }
        } else {
            val nextLink = findNextLink(link, packet)
            if (nextLink == null) {
                deliveredPackets += packet
            } else {
                deliverToLinkStart(nextLink, packet, deliveredPackets)
            }
        }
    }

    private fun shouldDrainLatency(link: UdpSimLink): Boolean =
        link.from in endpointNodes || (drainEndpointBoundLatency && link.to in endpointNodes)

    private fun refreshExternallyUpdatedLatencyQueues() {
        latencyLinksToDrainKeys.forEach { key ->
            latencyLinksToDrain.updateByKey(key) {
            }
        }
    }

    private fun latencyTaskDuration(link: UdpSimLink): Duration? =
        link.latencyQueue.nextTaskDuration()
            ?.let { link.latencyQueue.cumulativeAdvance + it - cumulativeAdvance }
            ?.coerceAtLeast(ZERO)

    private fun bandwidthTaskDuration(link: UdpSimLink): Duration? =
        link.bandwidthQueue.nextTaskDuration()
            ?.let { link.bandwidthQueue.cumulativeAdvance + it - cumulativeAdvance }
            ?.coerceAtLeast(ZERO)

    private fun advanceAllLatencyQueuesToCurrent() {
        network.links.forEach { link ->
            advanceLatencyQueueToCurrent(link)
        }
    }

    private fun advanceLatencyQueueToCurrent(link: UdpSimLink) {
        val advanceDuration = cumulativeAdvance - link.latencyQueue.cumulativeAdvance
        require(!advanceDuration.isNegative()) { "Latency queue advanced past engine time" }
        if (advanceDuration > ZERO) {
            link.latencyQueue.advance(advanceDuration)
        }
    }

    private fun advanceBandwidthQueueToCurrent(link: UdpSimLink) {
        val advanceDuration = cumulativeAdvance - link.bandwidthQueue.cumulativeAdvance
        require(!advanceDuration.isNegative()) { "Bandwidth queue advanced past engine time" }
        if (advanceDuration > ZERO) {
            link.bandwidthQueue.advance(advanceDuration)
        }
    }
}
