package io.libp2p.quicsim.sim

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.schedule.Controllable

interface SimNet<TPacket> : PacketProcessor<TPacket> {

    val allNodes: List<SimNode<TPacket>>
}