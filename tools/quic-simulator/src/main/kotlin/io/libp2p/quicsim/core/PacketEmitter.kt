package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.Controllable

interface PacketEmitter<TPacket> : Controllable {

    fun emitPackets(): List<TPacket>
}