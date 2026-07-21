package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.PacketProcessorB
import kotlin.collections.plusAssign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class AggregatePacketProcessor<TPacket, TKey>(
    delegates: Map<TKey, PacketProcessor<TPacket>>
) {

    private var cumulativeAdvanceMutable: Duration = ZERO
    val cumulativeAdvance get() = cumulativeAdvanceMutable
    var outboundPacketBuf = mutableListOf<TPacket>()

    data class WrappedProcessor<T>(
        val delegate: PacketProcessor<T>,
        val wrapper: PacketProcessorB<T> = PacketProcessorB(delegate)
    )

    private val wrappedDelegates = delegates
        .mapValues { (_, v) -> WrappedProcessor(v) }
    private val sortingDelegateList =
        ValueSortedMap(wrappedDelegates) { value ->
            value.wrapper.nextTaskPoint ?: Duration.INFINITE
        }

    fun deliverInbound(inboundData: List<TPacket>, toDelegateKey: TKey) {
        sortingDelegateList.updateByKey(toDelegateKey) { (_, wrapper) ->
            wrapper.advanceTillAndExecute(cumulativeAdvance)
            wrapper.deliverInbound(inboundData)
            outboundPacketBuf += wrapper.deliverOutbound()
        }
    }

    fun deliverOutbound(): List<TPacket> {
        val ret = outboundPacketBuf
        outboundPacketBuf = mutableListOf()
        return ret
    }

    fun advance(advanceDuration: Duration) {
        if (advanceDuration > ZERO && outboundPacketBuf.isNotEmpty()) {
            throw IllegalStateException("Advancing without draining outbound packets")
        }
        cumulativeAdvanceMutable += advanceDuration
    }

    fun executePending() {
        @Suppress("ControlFlowWithEmptyBody")
        while (
            sortingDelegateList.updateFirst { delegate ->
                val delegateNextTaskPoint = delegate.wrapper.nextTaskPoint ?: Duration.INFINITE
                if (delegateNextTaskPoint < cumulativeAdvance) {
                    throw IllegalStateException("Missed task point $delegateNextTaskPoint at $cumulativeAdvance")
                } else if (delegateNextTaskPoint == cumulativeAdvance) {
                    delegate.wrapper.advanceTillAndExecute(cumulativeAdvance)
                    outboundPacketBuf += delegate.wrapper.deliverOutbound()
                    true
                } else {
                    false
                }
            }
        ) {
        }
    }

    fun advanceAndExecuteAll(advanceDuration: Duration) {
        advance(advanceDuration)
        executePending()
    }

    fun nextTaskDuration(): Duration? =
        sortingDelegateList.getFirst().wrapper.nextTaskPoint?.let { it - cumulativeAdvanceMutable }
}
