package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable

interface SimCoreNet : PacketProcessor<SimCorePacket>, Controllable {

    val allNodes: List<SimCoreNode>

    fun getDestinationNode(packet: SimCorePacket): SimCoreNode

}