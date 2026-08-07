package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration

interface AggregateProcessor<TPacket> : Controllable {

    fun emitPackets(idx: Int): List<TPacket>

    fun receivePackets(idx: Int, packets: List<TPacket>)

    override fun advance(advanceDuration: Duration)

    override fun executePending()

    override fun nextTaskDuration(): Duration?
}