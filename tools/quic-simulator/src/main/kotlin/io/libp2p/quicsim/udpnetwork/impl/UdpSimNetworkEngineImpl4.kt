package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.schedule.PacketProcessorB
import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import io.libp2p.quicsim.udpnetwork.UdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.UdpSimLink
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork.Companion.findEndpoints
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork.Companion.nodesAndRouters
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import java.util.PriorityQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class UdpSimNetworkEngineImpl4(
    override val network: UdpSimNetwork
) : UdpSimNetworkEngine {

    private data class TimedPacket(
        val at: Duration,
        val packet: DatagramPacket
    )

    private data class LinkState(
        val link: UdpSimLink,
        val latencyQueue: LatencyQueueImpl<DatagramPacket>,
        val bandwidthQueue: TimedBandwidthQueue
    )

    private class TimedBandwidthQueue(
        bandwidthQueue: UdpSimBandwidthQueue
    ) {
        private val wrapper = PacketProcessorB(bandwidthQueue)

        fun enqueue(packets: List<DatagramPacket>, at: Duration) {
            if (packets.isEmpty()) {
                return
            }
            wrapper.advanceTillAndExecute(at)
            wrapper.deliverInbound(packets)
        }

        fun drainReadyUntil(targetTime: Duration, sink: MutableList<TimedPacket>) {
            while (true) {
                val nextTaskPoint = wrapper.nextTaskPoint ?: break
                if (nextTaskPoint > targetTime) {
                    break
                }
                wrapper.advanceTillAndExecute(nextTaskPoint)
                wrapper.deliverOutbound().forEach { packet ->
                    sink += TimedPacket(nextTaskPoint, packet)
                }
            }
        }

        fun nextTaskPoint(): Duration? =
            wrapper.nextTaskPoint
    }

    private var cumulativeAdvanceMutable: Duration = ZERO
    val currentTime get() = cumulativeAdvanceMutable
    private val pendingRoutedPackets = ArrayList<TimedPacket>()

    var innerPacketsCounter = 0L
    var deliveredPacketsCounter = 0L
    val totalPacketCount get() = innerPacketsCounter + deliveredPacketsCounter
    var lastDeliveredEndpointNodeIds: Set<String> = emptySet()
        private set

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
    private val inboundLinkIndexByRecipient = HashMap<InetSocketAddress, Int>()
    private val routers = network.nodesAndRouters - endpoints
    private val coreLatencyByRoute = routers.flatMap { from ->
        routers.map { to -> (from to to) to routerLatency(from, to) }
    }.toMap()

    init {
        require(outboundLinks.size == endpoints.size) { "Impl4 expects one outbound link per endpoint" }
        require(inboundLinks.size == endpoints.size) { "Impl4 expects one inbound link per endpoint" }
        network.links
            .filter { it.from in routers && it.to in routers }
            .forEach { link ->
                require(link.bandwidthQueue.bandwidth.isInfinite) {
                    "Impl4 fast path requires infinite bandwidth on intermediate links: ${link.from}->${link.to}"
                }
            }
    }

    override fun receivePackets(packets: List<DatagramPacket>) = TODO("Shouldn't be called")

    override fun emitPackets(): List<DatagramPacket> = TODO("Shouldn't be called")

    override fun advance(advanceDuration: Duration) = TODO("Short advances are not viable here. Use advanceUntil")

    fun advanceUntil(advanceDuration: Duration) {
        val targetTime = currentTime + advanceDuration
        val routedPackets = ArrayList<TimedPacket>()
        movePendingRoutedPackets(targetTime, routedPackets)
        val deliveredEndpointNodeIds = mutableSetOf<String>()

        outboundLinks.forEach { outboundLink ->
            val outboundRoutedPackets = ArrayList<TimedPacket>()
            val emittedPackets = outboundLink.latencyQueue.emitPacketsUntil(targetTime).sortedBy { it.at }
            emittedPackets.firstOrNull()?.let {
                outboundLink.bandwidthQueue.drainReadyUntil(it.at, outboundRoutedPackets)
            }
            emittedPackets.forEach { timedPackets ->
                outboundLink.bandwidthQueue.drainReadyUntil(timedPackets.at, outboundRoutedPackets)
                outboundLink.bandwidthQueue.enqueue(timedPackets.packets, timedPackets.at)
            }
            outboundLink.bandwidthQueue.drainReadyUntil(targetTime, outboundRoutedPackets)
            addCoreLatency(outboundLink, outboundRoutedPackets)
            routeReadyPackets(targetTime, outboundRoutedPackets, routedPackets)
        }

        val routedByInboundLink = Array(inboundLinks.size) { ArrayList<TimedPacket>() }
        routedPackets.forEach { timedPacket ->
            val inboundLinkIndex = inboundLinkIndex(timedPacket.packet)
            routedByInboundLink[inboundLinkIndex] += timedPacket
            innerPacketsCounter++
        }

        inboundLinks.forEachIndexed { index, inboundLink ->
            val arrivals = routedByInboundLink[index]
            if (arrivals.size > 1) {
                arrivals.sortBy { it.at }
            }
            val deliveredToEndpoint = ArrayList<TimedPacket>(arrivals.size)
            arrivals.firstOrNull()?.let {
                inboundLink.bandwidthQueue.drainReadyUntil(it.at, deliveredToEndpoint)
            }
            var arrivalIndex = 0
            while (arrivalIndex < arrivals.size) {
                val at = arrivals[arrivalIndex].at
                val sameTimePackets = mutableListOf<DatagramPacket>()
                while (arrivalIndex < arrivals.size && arrivals[arrivalIndex].at == at) {
                    sameTimePackets += arrivals[arrivalIndex].packet
                    arrivalIndex++
                }
                inboundLink.bandwidthQueue.drainReadyUntil(at, deliveredToEndpoint)
                inboundLink.bandwidthQueue.enqueue(sameTimePackets, at)
            }
            inboundLink.bandwidthQueue.drainReadyUntil(targetTime, deliveredToEndpoint)
            if (deliveredToEndpoint.isNotEmpty()) {
                deliveredEndpointNodeIds += inboundLink.link.to.id
            }
            receiveAtEndpoint(inboundLink, deliveredToEndpoint)
        }

        lastDeliveredEndpointNodeIds = deliveredEndpointNodeIds
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
        val nextCoreArrivalTask =
            pendingRoutedPackets.minOfOrNull { it.at } ?: Duration.INFINITE
        val nextTask = minOf(nextOutboundLatencyTask, nextOutboundBandwidthTask, nextInboundBandwidthTask, nextCoreArrivalTask)
        return if (nextTask == Duration.INFINITE) {
            null
        } else {
            nextTask - currentTime
        }
    }

    private fun UdpSimLink.toLinkState(): LinkState =
        LinkState(
            link = this,
            latencyQueue = latencyQueue as? LatencyQueueImpl<DatagramPacket>
                ?: error("Impl4 fast path requires LatencyQueueImpl"),
            bandwidthQueue = TimedBandwidthQueue(bandwidthQueue)
        )

    private fun movePendingRoutedPackets(
        targetTime: Duration,
        routedPackets: MutableList<TimedPacket>
    ) {
        val ready = pendingRoutedPackets.filter { it.at <= targetTime }
        if (ready.isEmpty()) {
            return
        }
        pendingRoutedPackets.removeAll(ready.toSet())
        routedPackets += ready
    }

    private fun routeReadyPackets(
        targetTime: Duration,
        packets: List<TimedPacket>,
        routedPackets: MutableList<TimedPacket>
    ) {
        packets.forEach { packet ->
            if (packet.at <= targetTime) {
                routedPackets += packet
            } else {
                pendingRoutedPackets += packet
            }
        }
    }

    private fun addCoreLatency(
        outboundLink: LinkState,
        routedPackets: MutableList<TimedPacket>
    ) {
        if (routedPackets.isEmpty()) {
            return
        }

        val sourceRouter = outboundLink.link.to
        routedPackets.indices.forEach { index ->
            val routedPacket = routedPackets[index]
            val destinationRouter = inboundLinks[inboundLinkIndex(routedPacket.packet)].link.from
            routedPackets[index] = routedPacket.copy(
                at = routedPacket.at + coreLatencyByRoute.getValue(sourceRouter to destinationRouter)
            )
        }
    }

    private fun inboundLinkIndex(packet: DatagramPacket): Int {
        val recipient = packet.recipient()
        return inboundLinkIndexByRecipient.getOrPut(recipient) {
            inboundLinkIndexByNodeId.getValue(recipient.hostString)
        }
    }

    private fun routerLatency(
        from: UdpSimNode,
        to: UdpSimNode
    ): Duration {
        if (from == to) {
            return ZERO
        }

        val adjacency = network.links
            .filter { it.from in routers && it.to in routers }
            .groupBy { it.from }
        val best = mutableMapOf<UdpSimNode, Duration>(from to ZERO)
        val queue = PriorityQueue<Pair<UdpSimNode, Duration>>(compareBy { it.second })
        queue += from to ZERO

        while (queue.isNotEmpty()) {
            val (node, latency) = queue.remove()
            if (latency != best[node]) {
                continue
            }
            if (node == to) {
                return latency
            }
            adjacency[node].orEmpty().forEach { link ->
                val nextLatency = latency + link.latencyQueue.minimalLatency
                if (nextLatency < (best[link.to] ?: Duration.INFINITE)) {
                    best[link.to] = nextLatency
                    queue += link.to to nextLatency
                }
            }
        }

        error("No router route from $from to $to")
    }
}
