package io.libp2p.quicsim.core

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

class SerialPacketProcessor<TPacket>(
    private val stages: List<PacketProcessor<TPacket>>
) : PacketProcessor<TPacket> {

    init {
        require(stages.isNotEmpty()) { "stages must not be empty" }
    }

    override fun deliver(inboundData: List<TPacket>): List<TPacket> {
        var outbound = deliverToStage(0, inboundData)
        stages.indices.forEach { stageIndex ->
            outbound += drainStage(stageIndex)
        }
        return outbound
    }

    override fun advance(advanceDuration: Duration) {
        stages.forEach { it.advance(advanceDuration) }
    }

    override fun executePending() {
        stages.forEach { it.executePending() }
    }

    override fun nextTaskDuration(): Duration? =
        stages.mapNotNull { it.nextTaskDuration() }.minOrNull()

    private fun deliverToStage(stageIndex: Int, inboundData: List<TPacket>): List<TPacket> {
        if (inboundData.isEmpty()) {
            return emptyList()
        }
        val stageOutbound = stages[stageIndex].deliver(inboundData)
        return if (stageIndex == stages.lastIndex) {
            stageOutbound
        } else {
            deliverToStage(stageIndex + 1, stageOutbound)
        }
    }

    private fun drainStage(stageIndex: Int): List<TPacket> {
        if (stages[stageIndex].nextTaskDuration() != ZERO) {
            return emptyList()
        }
        stages[stageIndex].executePending()
        val stageOutbound = stages[stageIndex].deliver(emptyList())
        return if (stageIndex == stages.lastIndex) {
            stageOutbound
        } else {
            deliverToStage(stageIndex + 1, stageOutbound) + drainStage(stageIndex + 1)
        }
    }
}
