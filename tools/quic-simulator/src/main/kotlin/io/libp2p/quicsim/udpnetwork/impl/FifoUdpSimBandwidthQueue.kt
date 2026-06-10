package io.libp2p.quicsim.udpnetwork.impl

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import kotlin.time.Duration

class FifoUdpSimBandwidthQueue(
    val bandwidth: Bandwidth,
    // unbound queue by default
    val maxQueueWaitTime: Duration = Duration.INFINITE,
) : QueueProcessorAdapter<UdpSimPacket>() {

    override fun enqueueInbound(inboundData: List<UdpSimPacket>, at: Duration) {
        var lastDequeueAt = max(lastQueuedAt ?: at, at)
        inboundData.forEach { packet ->
            val dequeueTime = lastDequeueAt + bandwidth.durationToTransfer(packet.bytes)
            if (dequeueTime - at <= maxQueueWaitTime) {
                enqueue(packet, dequeueTime)
                lastDequeueAt = dequeueTime
            } // else packet is dropped
        }
    }
}
