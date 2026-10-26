package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.udpSimBytes
import io.libp2p.quicsim.udpnetwork.udpSimFlowKey
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import java.util.ArrayDeque
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * FQ-CoDel as in RFC 8290: one CoDel queue (RFC 8289) per flow, scheduled by deficit round robin over
 * a list of new flows, served first, and a list of old ones. A flow is a source/destination host pair.
 *
 * - A flow that becomes active joins the new list with [quantumBytes] of credit, so a sparse flow --
 *   ACKs, votes -- is sent ahead of backlogged ones instead of waiting a whole round behind them.
 * - Each flow's CoDel starts dropping once its sojourn time has stayed above [targetDelay] for
 *   [interval], then drops at intervals of `interval / sqrt(count)` until the sojourn falls back.
 * - When [limitPackets] are queued in total, an arriving packet makes room by dropping the head of
 *   the flow with the largest backlog, as Linux does.
 *
 * A flow that empties while on the old list is forgotten along with its CoDel state, as in
 * marcopolo/simnet's implementation (Linux keeps the state per hash bucket instead).
 *
 * [maxQueueWaitTime] is not part of FQ-CoDel and is off by default; when given, a packet that has
 * waited longer is dropped before CoDel sees it.
 */
class FqCodelUdpSimBandwidthQueue(
    override val bandwidth: Bandwidth,
    override val maxQueueWaitTime: Duration = Duration.INFINITE,
    private val targetDelay: Duration = 5.milliseconds,
    private val interval: Duration = 100.milliseconds,
    private val quantumBytes: Int = 1514,
    private val limitPackets: Int = 10_240,
    private val mtuBytes: Int = 1_500
) : UdpSimBandwidthQueue {
    init {
        require(!targetDelay.isNegative()) { "targetDelay must not be negative" }
        require(interval.isPositive()) { "interval must be positive" }
        require(quantumBytes > 0) { "quantumBytes must be positive" }
        require(limitPackets > 0) { "limitPackets must be positive" }
    }

    private data class PacketEntry(
        val packet: DatagramPacket,
        val enqueuedAt: Duration
    )

    private data class ScheduledPacket(
        val packet: DatagramPacket,
        val deliverAt: Duration
    )

    private class Flow(val key: String) {
        val packets = ArrayDeque<PacketEntry>()
        var bytes = 0
        var credits = 0
        var listed = false

        // CoDel state, RFC 8289
        var dropping = false
        var firstAboveTime: Duration? = null
        var dropNext: Duration = Duration.ZERO
        var count = 0
        var lastCount = 0
    }

    private var currentTime: Duration = Duration.ZERO
    private var nextAvailableAt: Duration? = null
    private val flows = HashMap<String, Flow>()
    private val newFlows = ArrayDeque<Flow>()
    private val oldFlows = ArrayDeque<Flow>()
    private var queuedPackets = 0
    private var queuedBytes = 0
    private val scheduledPackets = ArrayDeque<ScheduledPacket>()

    override fun receivePackets(packets: List<DatagramPacket>) {
        packets.forEach { packet ->
            if (queuedPackets >= limitPackets) {
                dropFromFattestFlow()
            }
            val flow = flows.getOrPut(packet.udpSimFlowKey()) { Flow(packet.udpSimFlowKey()) }
            flow.packets += PacketEntry(packet, currentTime)
            flow.bytes += packet.udpSimBytes()
            queuedPackets++
            queuedBytes += packet.udpSimBytes()
            if (!flow.listed) {
                flow.listed = true
                flow.credits = quantumBytes
                newFlows += flow
            }
        }
    }

    override fun emitPackets(): List<DatagramPacket> {
        val ready = mutableListOf<DatagramPacket>()
        do {
            scheduleNextIfNeeded()
            val drained = drainReady()
            ready += drained
        } while (drained.isNotEmpty())
        return ready
    }

    override fun advance(advanceDuration: Duration) {
        currentTime += advanceDuration
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? {
        scheduleNextIfNeeded()
        return scheduledPackets.peekFirst()?.let { it.deliverAt - currentTime }
    }

    private fun scheduleNextIfNeeded() {
        if (scheduledPackets.isNotEmpty()) {
            return
        }
        val deliverAt = maxOf(nextAvailableAt ?: currentTime, currentTime)
        val packet = dequeue(deliverAt) ?: return
        scheduledPackets += ScheduledPacket(packet, deliverAt)
        nextAvailableAt = deliverAt + bandwidth.durationToTransfer(packet.udpSimBytes())
    }

    /** RFC 8290 section 4.2: new flows first, then old ones, each spending a quantum of credit per turn. */
    private fun dequeue(now: Duration): DatagramPacket? {
        while (true) {
            val fromNew = newFlows.isNotEmpty()
            val list = if (fromNew) newFlows else oldFlows
            val flow = list.peekFirst() ?: return null
            if (flow.credits <= 0) {
                flow.credits += quantumBytes
                list.removeFirst()
                oldFlows += flow
                continue
            }
            val packet = codelDequeue(flow, now)
            if (packet == null) {
                list.removeFirst()
                if (fromNew) {
                    // Through the old list before leaving, so a flow cannot starve the others by
                    // emptying and coming back as new every time.
                    oldFlows += flow
                } else {
                    flow.listed = false
                    flows.remove(flow.key)
                }
                continue
            }
            flow.credits -= packet.udpSimBytes()
            return packet
        }
    }

    /** RFC 8289 dequeue, with the Linux re-entry rule for the drop count. */
    private fun codelDequeue(flow: Flow, now: Duration): DatagramPacket? {
        var (entry, okToDrop) = popHead(flow, now)
        if (flow.dropping) {
            if (!okToDrop) {
                flow.dropping = false
            }
            while (flow.dropping && now >= flow.dropNext) {
                entry?.let { dropPacket(it.packet) }
                flow.count++
                val next = popHead(flow, now)
                entry = next.first
                if (!next.second) {
                    flow.dropping = false
                } else {
                    flow.dropNext = controlLaw(flow.dropNext, flow.count)
                }
            }
        } else if (okToDrop) {
            entry?.let { dropPacket(it.packet) }
            entry = popHead(flow, now).first
            flow.dropping = true
            val delta = flow.count - flow.lastCount
            flow.count = if (delta > 1 && now - flow.dropNext < interval * 16) delta else 1
            flow.dropNext = controlLaw(now, flow.count)
            flow.lastCount = flow.count
        }
        return entry?.packet
    }

    /** Takes the flow's head packet; the flag says CoDel may drop it. */
    private fun popHead(flow: Flow, now: Duration): Pair<PacketEntry?, Boolean> {
        while (true) {
            val entry = flow.packets.pollFirst()
            if (entry == null) {
                flow.firstAboveTime = null
                return null to false
            }
            removeFromBacklog(flow, entry)
            val sojourn = now - entry.enqueuedAt
            if (sojourn > maxQueueWaitTime) {
                dropPacket(entry.packet)
                continue
            }
            if (sojourn < targetDelay || queuedBytes <= mtuBytes) {
                flow.firstAboveTime = null
                return entry to false
            }
            val firstAbove = flow.firstAboveTime
            if (firstAbove == null) {
                flow.firstAboveTime = now + interval
                return entry to false
            }
            return entry to (now >= firstAbove)
        }
    }

    private fun dropFromFattestFlow() {
        val fattest = flows.values.filter { it.packets.isNotEmpty() }.maxByOrNull { it.bytes } ?: return
        val entry = fattest.packets.pollFirst()
        removeFromBacklog(fattest, entry)
        dropPacket(entry.packet)
    }

    private fun removeFromBacklog(flow: Flow, entry: PacketEntry) {
        flow.bytes -= entry.packet.udpSimBytes()
        queuedPackets--
        queuedBytes -= entry.packet.udpSimBytes()
    }

    private fun controlLaw(time: Duration, count: Int): Duration =
        time + (interval.inWholeNanoseconds / sqrt(count.toDouble())).toLong().nanoseconds

    private fun dropPacket(packet: DatagramPacket) {
        // Dropped by the qdisc: the packet never reaches a receiver, so the simulator owns its last reference.
        ReferenceCountUtil.safeRelease(packet)
    }

    private fun drainReady(): List<DatagramPacket> {
        val ready = mutableListOf<DatagramPacket>()
        while (scheduledPackets.isNotEmpty()) {
            val packet = scheduledPackets.peekFirst()
            if (packet.deliverAt < currentTime) {
                throw IllegalStateException("Internal error: Missed packet")
            }
            if (packet.deliverAt > currentTime) {
                break
            }
            ready += scheduledPackets.removeFirst().packet
        }
        return ready
    }
}
