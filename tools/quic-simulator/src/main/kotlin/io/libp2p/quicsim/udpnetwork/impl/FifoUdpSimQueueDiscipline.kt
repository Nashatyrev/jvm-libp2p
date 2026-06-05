package io.libp2p.quicsim.udpnetwork.impl

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.udpnetwork.impl.PacketProcessorAdapter
import io.libp2p.quicsim.core.SerialPacketProcessor
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.libp2p.quicsim.udpnetwork.UdpSimQueueDiscipline
import java.util.ArrayDeque
import kotlin.time.Duration

class FifoUdpSimQueueDiscipline(
    override val bandwidth: Bandwidth,
    override val latency: Duration,
    // unbound queue by default
    val maxQueueWaitTime: Duration = Duration.INFINITE,
    order: UdpSimQueueDisciplineOrder = UdpSimQueueDisciplineOrder.BANDWIDTH_THEN_LATENCY,
) : UdpSimQueueDiscipline {

    private val delegate = SerialPacketProcessor(
        when (order) {
            UdpSimQueueDisciplineOrder.BANDWIDTH_THEN_LATENCY -> listOf(
                FifoUdpSimBandwidthQueue(bandwidth, maxQueueWaitTime),
                UdpSimLatencyDelay(latency)
            )
            UdpSimQueueDisciplineOrder.LATENCY_THEN_BANDWIDTH -> listOf(
                UdpSimLatencyDelay(latency),
                FifoUdpSimBandwidthQueue(bandwidth, maxQueueWaitTime)
            )
        }
    )

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> =
        delegate.deliver(inboundData)

    override fun advance(advanceDuration: Duration) {
        delegate.advance(advanceDuration)
    }

    override fun executePending() {
        delegate.executePending()
    }

    override fun nextTaskDuration(): Duration? =
        delegate.nextTaskDuration()
}

enum class UdpSimQueueDisciplineOrder {
    BANDWIDTH_THEN_LATENCY,
    LATENCY_THEN_BANDWIDTH
}

class UdpSimLatencyDelay(
    val latency: Duration
) : PacketProcessorAdapter<UdpSimPacket>() {

    private data class DelayedPacket(
        val packet: UdpSimPacket,
        val deliverAt: Duration
    )

    private val queue = ArrayDeque<DelayedPacket>()

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
        inboundData.forEach { packet ->
            queue.addLast(DelayedPacket(packet, cumulativeAdvance + latency))
        }

        val ready = mutableListOf<UdpSimPacket>()
        while (queue.isNotEmpty()) {
            val packet = queue.peekFirst()
            if (packet.deliverAt < cumulativeAdvance) {
                throw IllegalStateException("Internal error: Missed packet")
            }
            if (packet.deliverAt > cumulativeAdvance) {
                break
            }
            ready += queue.removeFirst().packet
        }
        return ready
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        queue.peekFirst()?.let {
            it.deliverAt - cumulativeAdvance
        }
}

class FifoUdpSimBandwidthQueue(
    val bandwidth: Bandwidth,
    // unbound queue by default
    val maxQueueWaitTime: Duration = Duration.INFINITE,
) : PacketProcessorAdapter<UdpSimPacket>() {

    private data class QueuedPacket(
        val packet: UdpSimPacket,
        val dequeueAt: Duration
    )

    private val queue = ArrayDeque<QueuedPacket>()

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
        var lastDequeueAt = max(queue.peekLast()?.dequeueAt ?: cumulativeAdvance, cumulativeAdvance)
        inboundData.forEach { packet ->
            val dequeueTime = lastDequeueAt + bandwidth.durationToTransfer(packet.bytes)
            if (dequeueTime - cumulativeAdvance <= maxQueueWaitTime) {
                queue.addLast(
                    QueuedPacket(packet, dequeueTime)
                )
                lastDequeueAt = dequeueTime
            } // else packet is dropped
        }

        val ready = mutableListOf<UdpSimPacket>()
        while (queue.isNotEmpty()) {
            val packet = queue.peekFirst()
            if (packet.dequeueAt < cumulativeAdvance) {
                throw IllegalStateException("Internal error: Missed packet")
            }
            if (packet.dequeueAt > cumulativeAdvance) {
                break
            }
            ready += queue.removeFirst().packet
        }
        return ready
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        queue.peekFirst()?.let {
            it.dequeueAt - cumulativeAdvance
        }
}
