package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable

interface PacketProcessor<TPacket> : Controllable {

    fun deliver(inboundData: List<TPacket>): List<TPacket>
}