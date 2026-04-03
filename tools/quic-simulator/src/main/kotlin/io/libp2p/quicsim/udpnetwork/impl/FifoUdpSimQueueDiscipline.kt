package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.PacketProcessorAdapter
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
) : UdpSimQueueDiscipline, PacketProcessorAdapter<UdpSimPacket>() {

    private data class QueuedPacket(
        val packet: UdpSimPacket,
        val enqueueAt: Duration,
        val dequeueAt: Duration,
        val dequeueWithLatencyAt: Duration
    )

    private val queue = ArrayDeque<QueuedPacket>()

    override fun deliverImpl(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
        var lastDequeueAt = queue.peekLast()?.dequeueAt ?: cumulativeAdvance
        inboundData.forEach { packet ->
            val dequeueTime = lastDequeueAt + bandwidth.durationToTransfer(packet.bytes)
            if (dequeueTime - cumulativeAdvance <= maxQueueWaitTime) {
                queue.addLast(
                    QueuedPacket(packet, cumulativeAdvance, dequeueTime, dequeueTime + latency)
                )
                lastDequeueAt = dequeueTime
            } // else packet is dropped
        }


        val ready = mutableListOf<UdpSimPacket>()
        while (queue.isNotEmpty()) {
            val packet = queue.peekFirst()
            if (packet.dequeueWithLatencyAt < cumulativeAdvance) {
                throw IllegalStateException("Internal error: Missed packed")
            }
            if (packet.dequeueWithLatencyAt > cumulativeAdvance) {
                break
            }
            ready += queue.removeFirst().packet
        }
        return ready
    }

    override fun advanceAndExecuteAllImpl(advanceDuration: Duration) {
    }

    override fun nextTaskDurationImpl(): Duration? =
        queue.peekFirst()?.let {
            it.dequeueWithLatencyAt - cumulativeAdvance
        }
}
