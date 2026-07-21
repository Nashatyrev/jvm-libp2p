package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.UdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkDefaults
import io.libp2p.quicsim.udpnetwork.udpSimBytes
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class FifoUdpSimBandwidthQueue(
    override val bandwidth: Bandwidth,
    override val maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME,
) : QueueProcessorAdapter<DatagramPacket>(), UdpSimBandwidthQueue {
    private var nextAvailableAt: Duration? = null

    override fun enqueueInbound(inboundData: List<DatagramPacket>, at: Duration) {
        var availableAt = nextAvailableAt
        inboundData.forEach { packet ->
            val transferDuration = bandwidth.durationToTransfer(packet.udpSimBytes())
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
