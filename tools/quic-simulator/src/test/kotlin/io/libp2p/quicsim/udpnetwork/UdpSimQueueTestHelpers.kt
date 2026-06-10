package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.SerialPacketProcessor
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.UdpSimLatencyQueue
import kotlin.time.Duration

fun fifoUdpSimQueue(
    bandwidth: Bandwidth,
    latency: Duration,
    maxQueueWaitTime: Duration = Duration.INFINITE
): TestUdpSimQueue {
    val bandwidthQueue = FifoUdpSimBandwidthQueue(bandwidth, maxQueueWaitTime)
    val latencyQueue = UdpSimLatencyQueue(latency)
    return TestUdpSimQueue(
        bandwidthQueue = bandwidthQueue,
        latencyQueue = latencyQueue,
        delegate = SerialPacketProcessor(listOf(bandwidthQueue, latencyQueue))
    )
}

fun latencyThenBandwidthUdpSimQueue(
    bandwidth: Bandwidth,
    latency: Duration,
    maxQueueWaitTime: Duration = Duration.INFINITE
): TestUdpSimQueue {
    val bandwidthQueue = FifoUdpSimBandwidthQueue(bandwidth, maxQueueWaitTime)
    val latencyQueue = UdpSimLatencyQueue(latency)
    return TestUdpSimQueue(
        bandwidthQueue = bandwidthQueue,
        latencyQueue = latencyQueue,
        delegate = SerialPacketProcessor(listOf(latencyQueue, bandwidthQueue))
    )
}

class TestUdpSimQueue(
    val bandwidthQueue: FifoUdpSimBandwidthQueue,
    val latencyQueue: UdpSimLatencyQueue,
    private val delegate: PacketProcessor<UdpSimPacket>
) : PacketProcessor<UdpSimPacket> {

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
