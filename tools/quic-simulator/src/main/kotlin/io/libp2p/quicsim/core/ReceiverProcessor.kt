package io.libp2p.quicsim.core

import io.libp2p.quicsim.core.schedule.AggregateControllable
import kotlin.time.Duration

class ReceiverProcessor<TPacket>(
    private val receiver: PacketReceiver<TPacket>,
    private val processor: PacketProcessor<TPacket>,
) : PacketReceiver<TPacket> {

    val aggregateControllable = AggregateControllable(listOf(processor, receiver))

    override fun advance(advanceDuration: Duration) {
        aggregateControllable.advance(advanceDuration)
    }

    override fun executePending() {
        aggregateControllable.executePending()
        pumpPackets()
    }

    override fun nextTaskDuration(): Duration? =
        aggregateControllable.nextTaskDuration()

    private fun pumpPackets() {
        val packets = processor.emitPackets()
        if (packets.isNotEmpty()) {
            receiver.receivePackets(packets)
        }
    }

    override fun receivePackets(packets: List<TPacket>) {
        processor.receivePackets(packets)
        pumpPackets()
    }
}

fun <TPacket> PacketReceiver<TPacket>.processPackets(processor: PacketProcessor<TPacket>): PacketReceiver<TPacket> =
    ReceiverProcessor(this, processor)
