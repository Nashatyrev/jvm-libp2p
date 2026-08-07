package io.libp2p.quicsim.core.schedule.impl

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.schedule.AggregateControllable
import io.libp2p.quicsim.core.schedule.AggregateProcessor

class SimpleAggregateProcessor<TPacket>(
    private val processors: List<PacketProcessor<TPacket>>
) : AggregateControllable(processors), AggregateProcessor<TPacket> {

    override fun emitPackets(idx: Int): List<TPacket> =
        processors[idx].emitPackets()

    override fun receivePackets(idx: Int, packets: List<TPacket>) =
        processors[idx].receivePackets(packets)
}