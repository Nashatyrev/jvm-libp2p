package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable
import kotlin.time.Duration

interface PacketEmitter<TPacket> : Controllable {

    fun emitPackets(): List<TPacket>

    companion object {

        fun <TPacket> PacketEmitter<TPacket>.createPacketProcessorAdapter() = object : PacketProcessor<TPacket> {

            override fun deliver(inboundData: List<TPacket>): List<TPacket> {
                return this@createPacketProcessorAdapter.emitPackets()
            }

            override fun advance(advanceDuration: Duration) {
                this@createPacketProcessorAdapter.advance(advanceDuration)
            }

            override fun executePending() {
                this@createPacketProcessorAdapter.executePending()
            }

            override fun nextTaskDuration(): Duration? =
                this@createPacketProcessorAdapter.nextTaskDuration()

        }
    }
}