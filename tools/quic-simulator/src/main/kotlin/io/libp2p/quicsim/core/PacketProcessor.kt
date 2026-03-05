package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable

interface PacketProcessor : Controllable {

    fun deliver(inboundData: List<SimCorePacket>): List<SimCorePacket>
}