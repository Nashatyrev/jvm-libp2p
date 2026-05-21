package io.libp2p.quicsim.core

import kotlin.time.Duration

/**
 * Dispatches packets to one of delegate processors selected by [selector].
 *
 * Packets are grouped by selected delegate index preserving packet order within each group.
 */
class DispatchingPacketProcessor2<TKey, TPacket>(
    delegates: Map<TKey, PacketProcessor<TPacket>>,
    private val selector: (TPacket) -> TKey
) : PacketProcessor<TPacket> {

    private val aggregatePacketProcessor =
        AggregatePacketProcessor(delegates)

    override fun deliver(inboundData: List<TPacket>): List<TPacket> {
        inboundData.forEach { inPacket ->
            val destKey = selector(inPacket)
            aggregatePacketProcessor.deliverInbound(listOf(inPacket), destKey)
        }
        return aggregatePacketProcessor.deliverOutbound()
    }


    override fun advance(advanceDuration: Duration) {
        aggregatePacketProcessor.advance(advanceDuration)
    }

    override fun executePending() {
        aggregatePacketProcessor.executePending()
    }

    override fun nextTaskDuration(): Duration? =
        aggregatePacketProcessor.nextTaskDuration()
}
