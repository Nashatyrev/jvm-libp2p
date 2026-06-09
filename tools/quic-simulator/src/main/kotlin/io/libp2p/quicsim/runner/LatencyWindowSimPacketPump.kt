package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer
import io.libp2p.quicsim.sim.impl.SimNodeImpl
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.netty.channel.socket.DatagramPacket
import java.util.PriorityQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Runs nodes in isolated latency windows.
 *
 * During an epoch [currentTime, currentTime + minNodeLatency), each node consumes only inbound packets
 * already scheduled before the epoch. Outbound packets produced by nodes are committed after the epoch,
 * with delivery delayed by source outbound latency plus destination inbound latency.
 */
class LatencyWindowSimPacketPump(
    private val nodes: List<SimNodeImpl>,
    network: UdpSimNetwork,
    idAndIp: Collection<AbstractSimPacketBridge.IdMapEntry>,
    parallelism: Int,
) : Controllable, AutoCloseable {

    private data class InboundDatagram(
        val at: Duration,
        val sequence: Long,
        val packet: DatagramPacket,
    ) : Comparable<InboundDatagram> {
        override fun compareTo(other: InboundDatagram): Int {
            val atCompare = at.compareTo(other.at)
            return if (atCompare != 0) {
                atCompare
            } else {
                sequence.compareTo(other.sequence)
            }
        }
    }

    private data class OutboundDatagram(
        val at: Duration,
        val nodeId: Int,
        val sequence: Long,
        val packet: DatagramPacket,
    )

    private data class NodeState(
        val node: SimNodeImpl,
        var time: Duration = ZERO,
        var nextOutboundSequence: Long = 0,
        val inbound: PriorityQueue<InboundDatagram> = PriorityQueue(),
    )

    private val nanosPassed = AtomicLong(0)
    val monotonicTimer: MonotonicTimer = NanoMonotonicTimer(nanosPassed::get)

    private val executor: ExecutorService = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
    private val idToIp = idAndIp.associate { it.nodeId to it.ip }
    private val ipToId = idAndIp.associate { it.ip to it.nodeId }
    private val simNodeIdToNetworkId = nodes.associate { it.nodeId to ipToId.getValue(it.ip) }
    private val nodeStates = nodes.map { NodeState(it) }
    private val nodeStateByNetworkId = nodeStates.associateBy { simNodeIdToNetworkId.getValue(it.node.nodeId) }
    private val endpointLatency = endpointLatencies(network)
    private val minNodeLatency = endpointLatency.values.minOrNull() ?: ZERO
    private var currentTime = ZERO
    private var nextInboundSequence = 0L

    init {
        require(minNodeLatency > ZERO) {
            "Latency window simulation requires positive endpoint link latency"
        }
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }

        val horizon = currentTime + advanceDuration
        val outbound = advanceNodesTo(horizon)
            .sortedWith(
                compareBy<OutboundDatagram> { it.at }
                    .thenBy { it.nodeId }
                    .thenBy { it.sequence }
            )

        currentTime = horizon
        nanosPassed.updateAndGet { it + advanceDuration.inWholeNanoseconds }
        commitOutbound(outbound)
    }

    override fun nextTaskDuration(): Duration? {
        val hasNodeWork = nodeStates.any { it.node.nextTaskDuration() != null }
        val hasInbound = nodeStates.any { it.inbound.isNotEmpty() }
        return if (hasNodeWork || hasInbound) minNodeLatency else null
    }

    override fun close() {
        executor.shutdown()
    }

    private fun advanceNodesTo(horizon: Duration): List<OutboundDatagram> {
        if (nodes.isEmpty()) return emptyList()

        val chunks = nodeStates.chunked((nodeStates.size + parallelism() - 1) / parallelism())
        return executor.invokeAll(
            chunks.map { chunk ->
                Callable {
                    chunk.flatMap { nodeState ->
                        advanceNodeTo(nodeState, horizon)
                    }
                }
            }
        ).flatMap { it.get() }
    }

    private fun parallelism(): Int =
        (executor as? java.util.concurrent.ThreadPoolExecutor)?.corePoolSize ?: 1

    private fun advanceNodeTo(nodeState: NodeState, horizon: Duration): List<OutboundDatagram> {
        val outbound = mutableListOf<OutboundDatagram>()

        while (true) {
            val nextLocalAt = nodeState.node.nextTaskDuration()?.let { nodeState.time + it }
            val nextInboundAt = nodeState.inbound.peek()?.at
            val nextAt = listOfNotNull(nextLocalAt, nextInboundAt).minOrNull() ?: break

            if (nextAt >= horizon && nextAt != nodeState.time) break

            advanceNodeClock(nodeState, nextAt)
            outbound += drainOutbound(nodeState, nextAt)

            while (nodeState.inbound.peek()?.at == nextAt) {
                val inbound = nodeState.inbound.poll().packet
                outbound += recordOutbound(nodeState, nextAt, nodeState.node.deliver(listOf(inbound)))
                outbound += drainOutbound(nodeState, nextAt)
            }
        }

        if (horizon > nodeState.time) {
            advanceNodeClock(nodeState, horizon)
            outbound += drainOutbound(nodeState, horizon)
        }

        return outbound
    }

    private fun advanceNodeClock(nodeState: NodeState, targetTime: Duration) {
        val advance = targetTime - nodeState.time
        check(!advance.isNegative()) {
            "Cannot move node ${nodeState.node.nodeId} backwards from ${nodeState.time} to $targetTime"
        }
        nodeState.node.advanceAndExecuteAll(advance)
        nodeState.time = targetTime
    }

    private fun drainOutbound(nodeState: NodeState, at: Duration): List<OutboundDatagram> =
        recordOutbound(nodeState, at, nodeState.node.deliver(emptyList()))

    private fun recordOutbound(
        nodeState: NodeState,
        at: Duration,
        packets: List<DatagramPacket>
    ): List<OutboundDatagram> =
        packets.map { packet ->
            OutboundDatagram(
                at = at,
                nodeId = nodeState.node.nodeId,
                sequence = nodeState.nextOutboundSequence++,
                packet = packet,
            )
        }

    private fun commitOutbound(outbound: List<OutboundDatagram>) {
        outbound.forEach { datagram ->
            val srcNetworkId = ipToId.getValue(datagram.packet.sender().hostString)
            val dstNetworkId = ipToId.getValue(datagram.packet.recipient().hostString)
            val dstNodeState = nodeStateByNetworkId.getValue(dstNetworkId)
            val arrivalAt = datagram.at + endpointLatency.getValue(srcNetworkId) + endpointLatency.getValue(dstNetworkId)

            dstNodeState.inbound += InboundDatagram(
                at = arrivalAt,
                sequence = nextInboundSequence++,
                packet = datagram.packet,
            )
        }
    }

    private fun endpointLatencies(network: UdpSimNetwork): Map<String, Duration> {
        val linkedNodeIds = network.links.flatMap { listOf(it.from.id, it.to.id) }.toSet()
        return idToIp.keys.associateWith { nodeId ->
            network.links
                .filter { it.from.id == nodeId || it.to.id == nodeId }
                .map { it.latencyQueue.latency }
                .minOrNull()
                ?: error("No link latency found for endpoint $nodeId in nodes $linkedNodeIds")
        }
    }
}
