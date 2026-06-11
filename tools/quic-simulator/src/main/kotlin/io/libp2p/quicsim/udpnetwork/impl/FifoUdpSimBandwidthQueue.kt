package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import kotlin.time.Duration

class FifoUdpSimBandwidthQueue(
    val bandwidth: Bandwidth,
    // unbound queue by default
    val maxQueueWaitTime: Duration = Duration.INFINITE,
) : QueueProcessorAdapter<UdpSimPacket>() {
    private var nextAvailableAt: Duration? = null

    override fun enqueueInbound(inboundData: List<UdpSimPacket>, at: Duration) {
        var availableAt = nextAvailableAt
        inboundData.forEach { packet ->
            val transferDuration = bandwidth.durationToTransfer(packet.bytes)
            val previousAvailableAt = availableAt
            val dequeueTime = if (previousAvailableAt == null || previousAvailableAt <= at) {
                at
            } else {
                previousAvailableAt
            }
            if (dequeueTime - at <= maxQueueWaitTime) {
                enqueue(packet, dequeueTime)
                availableAt = dequeueTime + transferDuration
                nextAvailableAt = availableAt
            } // else packet is dropped
        }
    }
}
