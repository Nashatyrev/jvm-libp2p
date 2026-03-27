package io.libp2p.quicsim.core

import kotlin.time.Duration

class PacketProcessorWall<T>(
    val delegate: PacketProcessor<T>
) : PacketProcessor<T> {
    private var nextDuration = delegate.nextTaskDuration()

    override fun deliver(inboundData: List<T>): List<T> {
        return if (inboundData.isEmpty() && nextDuration != Duration.ZERO) {
            emptyList()
        } else {
            delegate.deliver(inboundData)
        }
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        nextDuration?.also {
            nextDuration = it - advanceDuration
        }
        if (nextDuration == null || nextDuration == Duration.ZERO) {
            delegate.advanceAndExecuteAll(advanceDuration)
            nextDuration = delegate.nextTaskDuration()
        }
    }

    override fun nextTaskDuration(): Duration? {
        return nextDuration
    }

    companion object {
        fun <T> PacketProcessor<T>.wall() = PacketProcessorWall(this)
    }
}