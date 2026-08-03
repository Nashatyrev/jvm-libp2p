package io.libp2p.quicsim.core

import kotlin.time.Duration

/**
 * Adapts a [PacketProcessor] with packet type [TInner] to work with packet type [TOuter].
 *
 * Received packets are mapped to [TInner] and emitted packets are mapped back to [TOuter].
 */
class MappingPacketProcessor<TOuter, TInner>(
    private val delegate: PacketProcessor<TInner>,
    private val mapToInner: (TOuter) -> TInner,
    private val mapToOuter: (TInner) -> TOuter
) : PacketProcessor<TOuter> {

    override fun receivePackets(packets: List<TOuter>) =
        delegate.receivePackets(packets.map(mapToInner))

    override fun emitPackets(): List<TOuter> =
        delegate.emitPackets().map(mapToOuter)

    override fun advance(advanceDuration: Duration) {
        delegate.advance(advanceDuration)
    }

    override fun executePending() {
        delegate.executePending()
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        delegate.advanceAndExecuteAll(advanceDuration)
    }

    override fun nextTaskDuration(): Duration? = delegate.nextTaskDuration()

    companion object {
        fun <TOuter, TInner> PacketProcessor<TInner>.map(
            mapToInner: (TOuter) -> TInner,
            mapToOuter: (TInner) -> TOuter
        ): PacketProcessor<TOuter> = MappingPacketProcessor(this, mapToInner, mapToOuter)
    }
}
