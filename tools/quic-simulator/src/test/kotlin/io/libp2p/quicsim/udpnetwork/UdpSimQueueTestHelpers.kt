package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.LatencyQueue
import io.libp2p.quicsim.core.PacketEmitter.Companion.createPacketProcessorAdapter
import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.PacketReceiver.Companion.createPacketProcessorAdapter
import io.libp2p.quicsim.core.SerialPacketProcessor
import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket
import java.net.InetSocketAddress
import kotlin.time.Duration

fun udpSimDatagram(
    bytes: Int,
    fromNodeId: String,
    toNodeId: String,
    fromPort: Int = 1,
    toPort: Int = 1
): DatagramPacket =
    DatagramPacket(
        Unpooled.wrappedBuffer(ByteArray(bytes)),
        udpSimAddress(toNodeId, toPort),
        udpSimAddress(fromNodeId, fromPort)
    )

private val udpSimAddresses = mutableMapOf<Pair<String, Int>, InetSocketAddress>()

private fun udpSimAddress(nodeId: String, port: Int): InetSocketAddress =
    synchronized(udpSimAddresses) {
        udpSimAddresses.getOrPut(nodeId to port) {
            InetSocketAddress.createUnresolved(nodeId, port)
        }
    }

fun fifoUdpSimQueue(
    bandwidth: Bandwidth,
    latency: Duration,
    maxQueueWaitTime: Duration = UdpSimNetworkDefaults.MAX_QUEUE_WAIT_TIME
): TestUdpSimQueue {
    val bandwidthQueue = FifoUdpSimBandwidthQueue(bandwidth, maxQueueWaitTime)
    val latencyQueue = LatencyQueueImpl<DatagramPacket>(latency)
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
    val latencyQueue = LatencyQueueImpl<DatagramPacket>(latency)
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
    val bandwidthQueue: UdpSimBandwidthQueue,
    val latencyQueue: LatencyQueue<DatagramPacket>,
    private val delegate: PacketProcessor<DatagramPacket>
) : PacketProcessor<DatagramPacket> {

    override fun deliver(inboundData: List<DatagramPacket>): List<DatagramPacket> =
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
