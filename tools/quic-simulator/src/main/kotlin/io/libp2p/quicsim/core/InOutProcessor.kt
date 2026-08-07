package io.libp2p.quicsim.core

import io.libp2p.quicsim.minOrNUll
import kotlin.time.Duration

class InOutProcessor<TPacket>(
    val emitter: NotifyingPacketEmitter<TPacket>,
    val receiver: PacketReceiver<TPacket>,
) : PacketProcessor<TPacket>, NotifyingPacketEmitter<TPacket> {

    override fun receivePackets(packets: List<TPacket>) =
        receiver.receivePackets(packets)

    override fun emitPackets(): List<TPacket> = emitter.emitPackets()

    override fun addPacketAddedListener(listener: () -> Unit) {
        emitter.addPacketAddedListener(listener)
    }

    override fun advance(advanceDuration: Duration) {
        emitter.advance(advanceDuration)
        receiver.advance(advanceDuration)
    }

    override fun executePending() {
        emitter.executePending()
        receiver.executePending()
    }

    override fun nextTaskDuration(): Duration? =
        minOrNUll(emitter.nextTaskDuration(), receiver.nextTaskDuration())
}
