package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkDefaults
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import java.util.ArrayDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class FqCodelUdpSimBandwidthQueue(
    override val bandwidth: Bandwidth,
    override val maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME,
    private val targetDelay: Duration = 5.milliseconds,
    private val interval: Duration = 100.milliseconds,
    private val quantumBytes: Int = 1514,
) : UdpSimBandwidthQueue {
    init {
        require(!targetDelay.isNegative()) { "targetDelay must not be negative" }
        require(interval.isPositive()) { "interval must be positive" }
        require(quantumBytes > 0) { "quantumBytes must be positive" }
    }

    private data class PacketEntry(
        val packet: UdpSimPacket,
        val enqueuedAt: Duration
    )

    private data class ScheduledPacket(
        val packet: UdpSimPacket,
        val deliverAt: Duration
    )

    private class FlowState {
        val packets = ArrayDeque<PacketEntry>()
        var deficitBytes = 0
        var active = false
        var firstAboveTargetAt: Duration? = null
    }

    private var currentTime: Duration = Duration.ZERO
    private var nextAvailableAt: Duration? = null
    private val flows = linkedMapOf<String, FlowState>()
    private val activeFlows = ArrayDeque<FlowState>()
    private val scheduledPackets = ArrayDeque<ScheduledPacket>()

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
        inboundData.forEach { packet ->
            val flow = flows.getOrPut(packet.flowKey) { FlowState() }
            flow.packets += PacketEntry(packet, currentTime)
            if (!flow.active) {
                flow.active = true
                activeFlows += flow
            }
        }

        val ready = mutableListOf<UdpSimPacket>()
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
        val packet = selectNextPacket(deliverAt) ?: return
        scheduledPackets += ScheduledPacket(packet, deliverAt)
        nextAvailableAt = deliverAt + bandwidth.durationToTransfer(packet.bytes)
    }

    private fun selectNextPacket(deliverAt: Duration): UdpSimPacket? {
        while (activeFlows.isNotEmpty()) {
            val flow = activeFlows.removeFirst()
            dropOverduePackets(flow, deliverAt)
            val head = flow.packets.peekFirst()
            if (head == null) {
                flow.active = false
                flow.deficitBytes = 0
                continue
            }

            while (flow.deficitBytes < head.packet.bytes) {
                flow.deficitBytes += quantumBytes
            }

            val entry = flow.packets.removeFirst()
            flow.deficitBytes -= entry.packet.bytes
            if (flow.packets.isEmpty()) {
                flow.active = false
            } else {
                activeFlows += flow
            }
            return entry.packet
        }
        return null
    }

    private fun dropOverduePackets(flow: FlowState, deliverAt: Duration) {
        while (flow.packets.isNotEmpty()) {
            val sojourn = deliverAt - flow.packets.peekFirst().enqueuedAt
            if (sojourn > maxQueueWaitTime) {
                flow.packets.removeFirst()
                flow.firstAboveTargetAt = null
                continue
            }

            if (sojourn <= targetDelay) {
                flow.firstAboveTargetAt = null
                return
            }

            val firstAboveTargetAt = flow.firstAboveTargetAt
            if (firstAboveTargetAt == null) {
                flow.firstAboveTargetAt = deliverAt + interval
                return
            }
            if (deliverAt < firstAboveTargetAt) {
                return
            }

            flow.packets.removeFirst()
            flow.firstAboveTargetAt = deliverAt + interval
        }
    }

    private fun drainReady(): List<UdpSimPacket> {
        val ready = mutableListOf<UdpSimPacket>()
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
