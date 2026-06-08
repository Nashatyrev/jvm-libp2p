package io.libp2p.quicsim.udpnetwork.impl

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import kotlin.time.Duration

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
