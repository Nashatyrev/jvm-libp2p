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

        var outboundPackets1: List<TPacket> = packetProcessor1.emitPackets()
        var outboundPackets2: List<TPacket> = packetProcessor2.emitPackets()

        do {
            val outboundPackets1new =
                if (outboundPackets2.isNotEmpty()) {
                    packetProcessor1.deliver(outboundPackets2)
                } else {
                    emptyList()
                }
            val outboundPackets2new =
                if (outboundPackets1.isNotEmpty()) {
                    packetProcessor2.deliver(outboundPackets1)
                } else {
                    emptyList()
                }

            outboundPackets1 = outboundPackets1new
            outboundPackets2 = outboundPackets2new
        } while (outboundPackets1new.isNotEmpty() || outboundPackets2new.isNotEmpty())
    }
}
