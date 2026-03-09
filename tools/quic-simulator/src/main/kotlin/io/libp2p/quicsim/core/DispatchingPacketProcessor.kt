package io.libp2p.quicsim.core

import kotlin.time.Duration

/**
 * Dispatches packets to one of delegate processors selected by [selector].
 *
 * Packets are grouped by selected delegate index preserving packet order within each group.
 */
class DispatchingPacketProcessor<TKey, TPacket>(
    val delegates: Map<TKey, PacketProcessor<TPacket>>,
    private val selector: (TPacket) -> TKey
) : PacketProcessor<TPacket> {

    override fun deliver(inboundData: List<TPacket>): List<TPacket> {
        val groupedByKey = inboundData.groupBy { selector(it) }

        val nonDelivered = groupedByKey.keys - delegates.keys
        if (nonDelivered.isNotEmpty()) {
            throw IllegalStateException("Some packets cannot be delivered: $nonDelivered")
        }

        val ret = delegates.flatMap { (key, delegate) ->
            val inboundForDelegate = groupedByKey[key] ?: emptyList()
            delegate.deliver(inboundForDelegate)
        }
        return ret
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        delegates.values.forEach { it.advanceAndExecuteAll(advanceDuration) }
    }

    override fun nextTaskDuration(): Duration? =
        delegates.values
            .mapNotNull { it.nextTaskDuration() }
            .minOrNull()
}
