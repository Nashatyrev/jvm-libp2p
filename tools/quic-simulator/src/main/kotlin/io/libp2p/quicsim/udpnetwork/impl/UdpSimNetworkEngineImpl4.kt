package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork.Companion.findEndpoints
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import java.util.ArrayDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class UdpSimNetworkEngineImpl4(
    override val network: UdpSimNetwork
) : UdpSimNetworkEngine {

    private data class TimedPacket(
        val at: Duration,
        val packet: UdpSimPacket
    )

    private data class LinkState(
        val link: UdpSimLink,
        val latencyQueue: LatencyQueueImpl<UdpSimPacket>,
        val bandwidthQueue: FastBandwidthQueue
    )

    private class FastBandwidthQueue(
        private val bandwidth: Bandwidth,
        private val maxQueueWaitTime: Duration
    ) {
        private val queue = ArrayDeque<TimedPacket>()
        private var nextAvailableAt: Duration? = null

        fun enqueue(packets: List<UdpSimPacket>, at: Duration) {
            var availableAt = nextAvailableAt
            packets.forEach { packet ->
                availableAt = enqueue(packet, at, availableAt)
            }
        }

        fun enqueue(packet: UdpSimPacket, at: Duration) {
            enqueue(packet, at, nextAvailableAt)
        }

        private fun enqueue(
            packet: UdpSimPacket,
            at: Duration,
            availableAt: Duration?
        ): Duration? {
            val dequeueTime = if (availableAt == null || availableAt <= at) {
                at
            } else {
                availableAt
            }
            return if (dequeueTime - at <= maxQueueWaitTime) {
                queue += TimedPacket(dequeueTime, packet)
                (dequeueTime + bandwidth.durationToTransfer(packet.bytes)).also {
                    nextAvailableAt = it
                }
            } else {
                availableAt
            }
        }

        fun drainReadyUntil(targetTime: Duration, sink: MutableList<TimedPacket>) {
            while (queue.isNotEmpty()) {
                val queuedPacket = queue.peekFirst()
                if (queuedPacket.at > targetTime) {
                    break
                }
                sink += queue.removeFirst()
            }
        }

        fun nextTaskPoint(): Duration? =
            queue.peekFirst()?.at
    }

    private var cumulativeAdvanceMutable: Duration = ZERO
    val currentTime get() = cumulativeAdvanceMutable

    var innerPacketsCounter = 0L
    var deliveredPacketsCounter = 0L
    val totalPacketCount get() = innerPacketsCounter + deliveredPacketsCounter

    private val endpoints = network.findEndpoints()
    private val outboundLinks = network.links
        .filter { it.from in endpoints }
        .map { it.toLinkState() }
    private val inboundLinks = network.links
        .filter { it.to in endpoints }
        .map { it.toLinkState() }
    private val inboundLinkIndexByNodeId = inboundLinks
        .mapIndexed { index, linkState -> linkState.link.to.id to index }
        .toMap()

    init {
        require(outboundLinks.size == endpoints.size) { "Impl4 expects one outbound link per endpoint" }
        require(inboundLinks.size == endpoints.size) { "Impl4 expects one inbound link per endpoint" }
    }

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> = TODO("Shouldn't be called")

    override fun advance(advanceDuration: Duration) = TODO("Short advances are not viable here. Use advanceUntil")

    fun advanceUntil(advanceDuration: Duration) {
        val targetTime = currentTime + advanceDuration
        val routedPackets = ArrayList<TimedPacket>()

        outboundLinks.forEach { outboundLink ->
            outboundLink.bandwidthQueue.drainReadyUntil(targetTime, routedPackets)
            outboundLink.latencyQueue.emitPacketsUntil(targetTime).forEach { timedPackets ->
                outboundLink.bandwidthQueue.enqueue(timedPackets.packets, timedPackets.at)
            }
            outboundLink.bandwidthQueue.drainReadyUntil(targetTime, routedPackets)
        }

        val routedByInboundLink = Array(inboundLinks.size) { ArrayList<TimedPacket>() }
        routedPackets.forEach { timedPacket ->
            val inboundLinkIndex = inboundLinkIndexByNodeId.getValue(timedPacket.packet.dstNodeId)
            routedByInboundLink[inboundLinkIndex] += timedPacket
            innerPacketsCounter++
        }

        inboundLinks.forEachIndexed { index, inboundLink ->
            val arrivals = routedByInboundLink[index]
            if (arrivals.size > 1) {
                arrivals.sortWith(compareBy<TimedPacket> { it.at }.thenBy { it.packet.id })
            }
            val deliveredToEndpoint = ArrayList<TimedPacket>(arrivals.size)
            inboundLink.bandwidthQueue.drainReadyUntil(targetTime, deliveredToEndpoint)
            arrivals.forEach { timedPacket ->
                inboundLink.bandwidthQueue.enqueue(timedPacket.packet, timedPacket.at)
            }
            inboundLink.bandwidthQueue.drainReadyUntil(targetTime, deliveredToEndpoint)
            receiveAtEndpoint(inboundLink, deliveredToEndpoint)
        }

        cumulativeAdvanceMutable = targetTime
    }

    private fun receiveAtEndpoint(
        inboundLink: LinkState,
        deliveredToEndpoint: List<TimedPacket>
    ) {
        if (deliveredToEndpoint.isEmpty()) {
            return
        }
        inboundLink.latencyQueue.receiveTimedPackets(
            packets = deliveredToEndpoint,
            timeExtractor = { it.at },
            packetExtractor = { it.packet }
        )
        deliveredPacketsCounter += deliveredToEndpoint.size
    }

    override fun executePending() {}

    override fun nextTaskDuration(): Duration? {
        val nextOutboundLatencyTask =
            outboundLinks.minOfOrNull { it.latencyQueue.nextEmitTime() ?: Duration.INFINITE } ?: Duration.INFINITE
        val nextOutboundBandwidthTask =
            outboundLinks.minOfOrNull { it.bandwidthQueue.nextTaskPoint() ?: Duration.INFINITE } ?: Duration.INFINITE
        val nextInboundBandwidthTask =
            inboundLinks.minOfOrNull { it.bandwidthQueue.nextTaskPoint() ?: Duration.INFINITE } ?: Duration.INFINITE
        val nextTask = minOf(nextOutboundLatencyTask, nextOutboundBandwidthTask, nextInboundBandwidthTask)
        return if (nextTask == Duration.INFINITE) {
            null
        } else {
            nextTask - currentTime
        }
    }

    private fun UdpSimLink.toLinkState(): LinkState =
        LinkState(
            link = this,
            latencyQueue = latencyQueue as? LatencyQueueImpl<UdpSimPacket>
                ?: error("Impl4 fast path requires LatencyQueueImpl"),
            bandwidthQueue = (bandwidthQueue as? FifoUdpSimBandwidthQueue)
                ?.let { FastBandwidthQueue(it.bandwidth, it.maxQueueWaitTime) }
                ?: error("Impl4 fast path currently supports only FIFO bandwidth queues")
        )
}
