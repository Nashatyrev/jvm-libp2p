package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.PacketProcessor
import java.util.ArrayDeque
import kotlin.time.Duration

abstract class QueueProcessorAdapter<TPacket> : PacketProcessor<TPacket> {
    private var cumulativeAdvanceMutable: Duration = Duration.Companion.ZERO
    val cumulativeAdvance get() = cumulativeAdvanceMutable
    protected val lastQueuedAt: Duration? get() = queue.peekLast()?.deliverAt

    protected data class QueuedPacket<TPacket>(
        val packet: TPacket,
        val deliverAt: Duration
    )

    protected val queue = ArrayDeque<QueuedPacket<TPacket>>()

    final override fun receivePackets(packets: List<TPacket>) =
        enqueueInbound(packets, cumulativeAdvance)

    final override fun emitPackets(): List<TPacket> =
        drainReady(cumulativeAdvance)

    override fun advance(advanceDuration: Duration) {
        cumulativeAdvanceMutable += advanceDuration
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        nextTaskDurationAt(cumulativeAdvance)

    protected fun enqueue(packet: TPacket, deliverAt: Duration) {
        queue.addLast(QueuedPacket(packet, deliverAt))
    }

    protected fun nextTaskDurationAt(at: Duration): Duration? =
        queue.peekFirst()?.let {
            it.deliverAt - at
        }

    protected abstract fun enqueueInbound(inboundData: List<TPacket>, at: Duration)

    private fun drainReady(at: Duration): List<TPacket> {
        val ready = mutableListOf<TPacket>()
        while (queue.isNotEmpty()) {
            val packet = queue.peekFirst()
            if (packet.deliverAt < at) {
                throw IllegalStateException("Internal error: Missed packet")
            }
            if (packet.deliverAt > at) {
                break
            }
            ready += queue.removeFirst().packet
        }
        return ready
    }
}
