package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import kotlin.time.Duration

class UdpSimLatencyQueue(
    val latency: Duration
) : QueueProcessorAdapter<UdpSimPacket>() {

    val aheadProcessor: PacketProcessor<UdpSimPacket> = AheadProcessor()

    override fun enqueueInbound(inboundData: List<UdpSimPacket>, at: Duration) {
        inboundData.forEach { packet ->
            enqueue(packet, at + latency)
        }
    }

    private inner class AheadProcessor : PacketProcessor<UdpSimPacket> {
        private var cumulativeAdvanceMutable: Duration = cumulativeAdvance
        private val cumulativeAdvance: Duration
            get() {
                if (cumulativeAdvanceMutable < this@UdpSimLatencyQueue.cumulativeAdvance) {
                    cumulativeAdvanceMutable = this@UdpSimLatencyQueue.cumulativeAdvance
                }
                return cumulativeAdvanceMutable
            }

        override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> =
            deliverAt(inboundData, cumulativeAdvance)

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

        override fun nextTaskDuration(): Duration? =
            nextTaskDurationAt(cumulativeAdvance)
    }
}