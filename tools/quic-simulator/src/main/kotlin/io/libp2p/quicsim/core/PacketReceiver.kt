package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable

interface PacketReceiver<TPacket> : Controllable {

    fun receivePackets(packets: List<TPacket>)
}