package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.PacketProcessor
import java.util.ArrayDeque
import kotlin.time.Duration

abstract class QueueProcessorAdapter<TPacket> : PacketProcessor<TPacket> {
    private var cumulativeAdvanceMutable: Duration = Duration.Companion.ZERO
    val cumulativeAdvance get() = cumulativeAdvanceMutable
    protected val lastQueuedAt: Duration? get() = queue.peekLast()?.deliverAt

    private data class QueuedPacket<TPacket>(
        val packet: TPacket,
        val deliverAt: Duration
    )

    private val queue = ArrayDeque<QueuedPacket<TPacket>>()

    final override fun deliver(inboundData: List<TPacket>): List<TPacket> {
        enqueueInbound(inboundData)
        return drainReady()
    }

    override fun advance(advanceDuration: Duration) {
        cumulativeAdvanceMutable += advanceDuration
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        queue.peekFirst()?.let {
            it.deliverAt - cumulativeAdvance
        }

    protected fun enqueue(packet: TPacket, deliverAt: Duration) {
        queue.addLast(QueuedPacket(packet, deliverAt))
    }

    protected abstract fun enqueueInbound(inboundData: List<TPacket>)

    private fun drainReady(): List<TPacket> {
        val ready = mutableListOf<TPacket>()
        while (queue.isNotEmpty()) {
            val packet = queue.peekFirst()
            if (packet.deliverAt < cumulativeAdvance) {
                throw IllegalStateException("Internal error: Missed packet")
            }
            if (packet.deliverAt > cumulativeAdvance) {
                break
            }
            ready += queue.removeFirst().packet
        }
        return ready
    }
}
