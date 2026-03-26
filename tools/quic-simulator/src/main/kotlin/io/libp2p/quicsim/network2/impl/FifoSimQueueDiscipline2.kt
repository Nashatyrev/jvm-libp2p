package io.libp2p.quicsim.network2.impl

import io.libp2p.quicsim.network2.Bandwidth
import io.libp2p.quicsim.network2.SimPacket
import io.libp2p.quicsim.network2.SimQueueDiscipline2
import java.util.ArrayDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class FifoSimQueueDiscipline2(
    override val bandwidth: Bandwidth,
    override val latency: Duration,
    // unbound queue by default
    val maxQueueWaitTime: Duration = Duration.INFINITE,
) : SimQueueDiscipline2 {

    private data class QueuedPacket(
        val packet: SimPacket,
        val enqueueAt: Duration,
        val dequeueAt: Duration,
        val dequeueWithLatencyAt: Duration
    )

    private val queue = ArrayDeque<QueuedPacket>()
    private var currentTime: Duration = ZERO

    override fun deliver(inboundData: List<SimPacket>): List<SimPacket> {
        var lastDequeueAt = queue.lastOrNull()?.dequeueAt ?: currentTime
        inboundData.forEach { packet ->
            val dequeueTime = lastDequeueAt + bandwidth.durationToTransfer(packet.bytes)
            if (dequeueTime - currentTime <= maxQueueWaitTime) {
                queue.addLast(
                    QueuedPacket(packet, currentTime, dequeueTime, dequeueTime + latency)
                )
                lastDequeueAt = dequeueTime
            } // else packet is dropped
        }


        val ready = mutableListOf<SimPacket>()
        while (queue.isNotEmpty()) {
            val packet = queue.first()
            if (packet.dequeueWithLatencyAt < currentTime) {
                throw IllegalStateException("Internal error: Missed packed")
            }
            if (packet.dequeueWithLatencyAt > currentTime) {
                break
            }
            ready += queue.removeFirst().packet
        }
        return ready
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        currentTime += advanceDuration
    }

    override fun nextTaskDuration(): Duration? =
        queue.firstOrNull()?.let {
            it.dequeueWithLatencyAt - currentTime
        }
}
