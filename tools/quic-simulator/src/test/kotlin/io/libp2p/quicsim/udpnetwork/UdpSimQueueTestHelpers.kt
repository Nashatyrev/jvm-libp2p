package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.LatencyQueue
import io.libp2p.quicsim.core.PacketEmitter.Companion.createPacketProcessorAdapter
import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.PacketReceiver.Companion.createPacketProcessorAdapter
import io.libp2p.quicsim.core.SerialPacketProcessor
import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import kotlin.time.Duration

fun fifoUdpSimQueue(
    bandwidth: Bandwidth,
    latency: Duration,
    maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
): TestUdpSimQueue {
    val bandwidthQueue = FifoUdpSimBandwidthQueue(bandwidth, maxQueueWaitTime)
    val latencyQueue = LatencyQueueImpl<UdpSimPacket>(latency)
    return TestUdpSimQueue(
        bandwidthQueue = bandwidthQueue,
        latencyQueue = latencyQueue,
        delegate = SerialPacketProcessor(
            listOf(
                bandwidthQueue,
                latencyQueue.receiver.createPacketProcessorAdapter()
            )
        )
    )
}

fun latencyThenBandwidthUdpSimQueue(
    bandwidth: Bandwidth,
    latency: Duration,
    maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
): TestUdpSimQueue {
    val bandwidthQueue = FifoUdpSimBandwidthQueue(bandwidth, maxQueueWaitTime)
    val latencyQueue = LatencyQueueImpl<UdpSimPacket>(latency)
    return TestUdpSimQueue(
        bandwidthQueue = bandwidthQueue,
        latencyQueue = latencyQueue,
        delegate = SerialPacketProcessor(
            listOf(
                latencyQueue.emitter.createPacketProcessorAdapter(),
                bandwidthQueue
            )
        )
    )
}

class TestUdpSimQueue(
    val bandwidthQueue: FifoUdpSimBandwidthQueue,
    val latencyQueue: LatencyQueue<UdpSimPacket>,
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
