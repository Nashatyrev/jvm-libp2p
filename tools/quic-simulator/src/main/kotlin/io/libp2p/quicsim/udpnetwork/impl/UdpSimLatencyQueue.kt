package io.libp2p.quicsim.udpnetwork.impl

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import java.util.ArrayDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class UdpSimLatencyQueue(
    val latency: Duration
) : PacketProcessor<UdpSimPacket> {
    private val lock = Any()
    private var cumulativeAdvanceMutable: Duration = ZERO
    val cumulativeAdvance: Duration get() = synchronized(lock) { cumulativeAdvanceMutable }

    private data class QueuedPacket(
        val packet: UdpSimPacket,
        val deliverAt: Duration
    )

    private val queue = ArrayDeque<QueuedPacket>()

    val aheadProcessor: PacketProcessor<UdpSimPacket> = AheadProcessor()
    val aheadEnqueueProcessor: PacketProcessor<UdpSimPacket> = AheadEnqueueProcessor()

    override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> =
        synchronized(lock) {
            deliverAt(inboundData, cumulativeAdvanceMutable)
        }

    override fun advance(advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
        synchronized(lock) {
            cumulativeAdvanceMutable += advanceDuration
        }
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        synchronized(lock) {
            nextTaskDurationAt(cumulativeAdvanceMutable)
        }

    fun enqueueInboundWithDeliveryFloor(inboundData: List<UdpSimPacket>, deliveryFloor: Duration) {
        synchronized(lock) {
            inboundData.forEach { packet ->
                enqueue(packet, max(cumulativeAdvanceMutable + latency, deliveryFloor))
            }
        }
    }

    private fun deliverAt(inboundData: List<UdpSimPacket>, at: Duration): List<UdpSimPacket> {
        enqueueInboundAt(inboundData, at)
        return drainReady(at)
    }

    private fun enqueueInboundAt(inboundData: List<UdpSimPacket>, at: Duration) {
        inboundData.forEach { packet ->
            enqueue(packet, at + latency)
        }
    }

    private fun enqueue(packet: UdpSimPacket, deliverAt: Duration) {
        queue.addLast(QueuedPacket(packet, deliverAt))
    }

    private fun nextTaskDurationAt(at: Duration): Duration? =
        queue.peekFirst()?.let {
            it.deliverAt - at
        }

    private fun drainReady(at: Duration): List<UdpSimPacket> {
        val ready = mutableListOf<UdpSimPacket>()
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

    private abstract inner class AbstractAheadProcessor : PacketProcessor<UdpSimPacket> {
        private var cumulativeAdvanceMutable: Duration = ZERO

        protected fun currentTime(): Duration =
            max(cumulativeAdvanceMutable, this@UdpSimLatencyQueue.cumulativeAdvanceMutable)

        override fun advance(advanceDuration: Duration) {
            require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
            synchronized(lock) {
                val nextTime = currentTime() + advanceDuration
                val maxAheadTime = this@UdpSimLatencyQueue.cumulativeAdvanceMutable + latency
                require(nextTime <= maxAheadTime) {
                    "Ahead latency processor cannot advance past latency bound $maxAheadTime"
                }
                cumulativeAdvanceMutable = nextTime
            }
        }

        override fun executePending() {
        }
    }

    private inner class AheadProcessor : AbstractAheadProcessor() {
        override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> =
            synchronized(lock) {
                deliverAt(inboundData, currentTime())
            }

        override fun nextTaskDuration(): Duration? =
            synchronized(lock) {
                nextTaskDurationAt(currentTime())
            }
    }

    private inner class AheadEnqueueProcessor : AbstractAheadProcessor() {
        override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> =
            synchronized(lock) {
                enqueueInboundAt(inboundData, currentTime())
                emptyList()
            }

        override fun nextTaskDuration(): Duration? =
            null
    }
}
