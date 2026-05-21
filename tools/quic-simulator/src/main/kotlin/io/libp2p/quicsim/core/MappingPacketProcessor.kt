package io.libp2p.quicsim.core

import kotlin.time.Duration

/**
 * Adapts a [PacketProcessor] with packet type [TInner] to work with packet type [TOuter].
 *
 * Packets are mapped to [TInner] before delegate delivery and mapped back to [TOuter] on delegate output.
 */
class MappingPacketProcessor<TOuter, TInner>(
    private val delegate: PacketProcessor<TInner>,
    private val mapToInner: (TOuter) -> TInner,
    private val mapToOuter: (TInner) -> TOuter
) : PacketProcessor<TOuter> {

    override fun deliver(inboundData: List<TOuter>): List<TOuter> {
        val mappedInbound = inboundData.map(mapToInner)
        return delegate.deliver(mappedInbound).map(mapToOuter)
    }

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
}
