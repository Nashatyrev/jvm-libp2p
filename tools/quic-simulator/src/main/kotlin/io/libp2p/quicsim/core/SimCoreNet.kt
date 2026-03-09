package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable

interface SimCoreNet<TPacket> : PacketProcessor<TPacket>, Controllable {

    val allNodes: List<SimCoreNode<TPacket>>
}