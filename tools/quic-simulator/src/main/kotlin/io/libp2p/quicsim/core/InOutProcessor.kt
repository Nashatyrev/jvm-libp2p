package io.libp2p.quicsim.core

import kotlin.time.Duration

class InOutProcessor<TPacket>(
    val emitter: PacketEmitter<TPacket>,
    val receiver: PacketReceiver<TPacket>,
) : PacketProcessor<TPacket> {

    override fun receivePackets(packets: List<TPacket>) =
        receiver.receivePackets(packets)

    override fun emitPackets(): List<TPacket> = emitter.emitPackets()

    override fun advance(advanceDuration: Duration) {
        emitter.advance(advanceDuration)
        receiver.advance(advanceDuration)
    }

    override fun executePending() {
        emitter.executePending()
        receiver.executePending()
    }

    override fun nextTaskDuration(): Duration? =
        emitter.nextTaskDuration()
}
