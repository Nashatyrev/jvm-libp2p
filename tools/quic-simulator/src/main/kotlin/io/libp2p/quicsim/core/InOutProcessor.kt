package io.libp2p.quicsim.core

import kotlin.time.Duration

class InOutProcessor<TPacket>(
    val inProcessor: PacketProcessor<TPacket>,
    val outProcessor: PacketProcessor<TPacket>,
) : PacketProcessor<TPacket> {

    override fun deliver(inboundData: List<TPacket>): List<TPacket> {
        if (inboundData.isNotEmpty()) {
            val shouldBeEmpty = outProcessor.deliver(inboundData)
            check(shouldBeEmpty.isEmpty())
        }
        return inProcessor.deliver(emptyList())
    }

    override fun advance(advanceDuration: Duration) {
        inProcessor.advance(advanceDuration)
        outProcessor.advance(advanceDuration)
    }

    override fun executePending() {
        inProcessor.executePending()
        outProcessor.executePending()
    }

    override fun nextTaskDuration(): Duration? =
        inProcessor.nextTaskDuration()
}
