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

    override fun receivePackets(packets: List<TPacket>) {
        val groupedByKey = packets.groupBy { selector(it) }

        val nonDelivered = groupedByKey.keys - delegates.keys
        if (nonDelivered.isNotEmpty()) {
            throw IllegalStateException("Some packets cannot be delivered: $nonDelivered")
        }

        delegates.forEach { (key, delegate) ->
            delegate.receivePackets(groupedByKey[key] ?: emptyList())
        }
    }

    override fun emitPackets(): List<TPacket> =
        delegates.values.flatMap { it.emitPackets() }

    override fun advance(advanceDuration: Duration) {
        delegates.values.forEach { it.advance(advanceDuration) }
    }

    override fun executePending() {
        delegates.values.forEach { it.executePending() }
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        delegates.values.forEach { it.advanceAndExecuteAll(advanceDuration) }
    }

    override fun nextTaskDuration(): Duration? =
        delegates.values
            .mapNotNull { it.nextTaskDuration() }
            .minOrNull()
}
