package io.libp2p.quicsim.core

interface PacketProcessor<TPacket> : PacketEmitter<TPacket>, PacketReceiver<TPacket>

/** Receives [inboundData] and immediately emits all ready packets. */
fun <TPacket> PacketProcessor<TPacket>.deliver(inboundData: List<TPacket>): List<TPacket> {
    receivePackets(inboundData)
    return emitPackets()
}
