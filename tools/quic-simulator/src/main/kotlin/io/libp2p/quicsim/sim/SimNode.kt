package io.libp2p.quicsim.sim

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.schedule.Controllable

interface SimNode<TPacket> : PacketProcessor<TPacket> {

    val ip: String
}