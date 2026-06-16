package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable
import kotlin.time.Duration

interface PacketReceiver<TPacket> : Controllable {

    fun receivePackets(packets: List<TPacket>)

    companion object {

        fun <TPacket> PacketReceiver<TPacket>.createPacketProcessorAdapter() = object : PacketProcessor<TPacket> {

            override fun deliver(inboundData: List<TPacket>): List<TPacket> {
                this@createPacketProcessorAdapter.receivePackets(inboundData)
                return emptyList()
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