package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.schedule.Controllable
import kotlin.time.Duration

class ControllablePacketPump<TPacket>(
    private val packetProcessor1: PacketProcessor<TPacket>,
    private val packetProcessor2: PacketProcessor<TPacket>,
) : Controllable {

    val aggregateControllable = AggregateControllable(listOf(packetProcessor1, packetProcessor2))

    override fun advance(advanceDuration: Duration) {
        aggregateControllable.advance(advanceDuration)
    }

    override fun executePending() {
        aggregateControllable.executePending()
        pumpPackets()
    }

    override fun nextTaskDuration(): Duration? =
        aggregateControllable.nextTaskDuration()

    fun pumpPackets() {

        var outboundPackets1: List<TPacket> = emptyList()
        var outboundPackets2: List<TPacket> = emptyList()

        do {
            val outboundPackets1new = packetProcessor1.deliver(outboundPackets2)
            val outboundPackets2new = packetProcessor2.deliver(outboundPackets1)

            outboundPackets1 = outboundPackets1new
            outboundPackets2 = outboundPackets2new
        } while (outboundPackets1new.isNotEmpty() || outboundPackets2new.isNotEmpty())
    }
}
