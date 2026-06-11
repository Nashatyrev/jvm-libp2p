package io.libp2p.quicsim.sim

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.MonotonicTimer

interface SimNode<TPacket> : PacketProcessor<TPacket> {

    val ip: String

    val nodeTime: MonotonicTimer
}