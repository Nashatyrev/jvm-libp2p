package io.libp2p.quicsim.udpnetwork.impl

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.core.SerialPacketProcessor
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.libp2p.quicsim.udpnetwork.UdpSimQueueDiscipline
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
) : QueueProcessorAdapter<UdpSimPacket>() {

    override fun enqueueInbound(inboundData: List<UdpSimPacket>) {
        inboundData.forEach { packet ->
            enqueue(packet, cumulativeAdvance + latency)
        }
    }
}

class FifoUdpSimBandwidthQueue(
    val bandwidth: Bandwidth,
    // unbound queue by default
    val maxQueueWaitTime: Duration = Duration.INFINITE,
) : QueueProcessorAdapter<UdpSimPacket>() {

    override fun enqueueInbound(inboundData: List<UdpSimPacket>) {
        var lastDequeueAt = max(lastQueuedAt ?: cumulativeAdvance, cumulativeAdvance)
        inboundData.forEach { packet ->
            val dequeueTime = lastDequeueAt + bandwidth.durationToTransfer(packet.bytes)
            if (dequeueTime - cumulativeAdvance <= maxQueueWaitTime) {
                enqueue(packet, dequeueTime)
                lastDequeueAt = dequeueTime
            } // else packet is dropped
        }
    }
}
