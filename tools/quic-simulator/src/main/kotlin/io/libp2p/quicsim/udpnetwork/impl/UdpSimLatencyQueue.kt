package io.libp2p.quicsim.udpnetwork.impl

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import kotlin.time.Duration

class UdpSimLatencyQueue(
    val latency: Duration
) : QueueProcessorAdapter<UdpSimPacket>() {

    val aheadProcessor: PacketProcessor<UdpSimPacket> = AheadProcessor()
    val aheadEnqueueProcessor: PacketProcessor<UdpSimPacket> = AheadEnqueueProcessor()

    override fun enqueueInbound(inboundData: List<UdpSimPacket>, at: Duration) {
        inboundData.forEach { packet ->
            enqueue(packet, at + latency)
        }
    }

    fun enqueueInboundWithDeliveryFloor(inboundData: List<UdpSimPacket>, deliveryFloor: Duration) {
        inboundData.forEach { packet ->
            enqueue(packet, max(cumulativeAdvance + latency, deliveryFloor))
        }
    }

    private abstract inner class AbstractAheadProcessor : PacketProcessor<UdpSimPacket> {
        private var cumulativeAdvanceMutable: Duration = cumulativeAdvance
        protected val cumulativeAdvance: Duration
            get() {
                if (cumulativeAdvanceMutable < this@UdpSimLatencyQueue.cumulativeAdvance) {
                    cumulativeAdvanceMutable = this@UdpSimLatencyQueue.cumulativeAdvance
                }
                return cumulativeAdvanceMutable
            }

        override fun advance(advanceDuration: Duration) {
            require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
            val nextTime = cumulativeAdvance + advanceDuration
            val maxAheadTime = this@UdpSimLatencyQueue.cumulativeAdvance + latency
            require(nextTime <= maxAheadTime) {
                "Ahead latency processor cannot advance past latency bound $maxAheadTime"
            }
            cumulativeAdvanceMutable = nextTime
        }

        override fun executePending() {
        }
    }

    private inner class AheadProcessor : AbstractAheadProcessor() {
        override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> =
            deliverAt(inboundData, cumulativeAdvance)

        override fun nextTaskDuration(): Duration? =
            nextTaskDurationAt(cumulativeAdvance)
    }

    private inner class AheadEnqueueProcessor : AbstractAheadProcessor() {
        override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
            enqueueInbound(inboundData, cumulativeAdvance)
            return emptyList()
        }

        override fun nextTaskDuration(): Duration? =
            null
    }
}
