package io.libp2p.quicsim.sim.impl

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.sim.impl.netty.SimDatagramChannel
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Adapts [io.libp2p.quicsim.sim.impl.netty.SimDatagramChannel] to [PacketProcessor] contract where packets are exchanged as Netty [DatagramPacket].
 */
class EmbeddedChannelDatagramPacketProcessor(
    val channel: SimDatagramChannel
) : PacketProcessor<DatagramPacket> {
    private var nextScheduledTaskDelay: Duration? = null

    override fun receivePackets(packets: List<DatagramPacket>) {
        packets.forEach {
            channel.writeInbound(it)
        }
        runPendingAndScheduledTasks()
    }

    override fun emitPackets(): List<DatagramPacket> = drainOutbound()

    override fun advance(advanceDuration: Duration) {
        channel.ticker.time += advanceDuration
        // Time is provided by EmbeddedChannel custom Ticker (NettyTicker), so we should not call advanceTimeBy().
    }

    override fun executePending() {
        runPendingAndScheduledTasks()
    }

    override fun nextTaskDuration(): Duration? =
        if (channel.hasPendingTasks()) Duration.ZERO else nextScheduledTaskDelay

    private fun runPendingAndScheduledTasks() {
        channel.runPendingTasks()
        val nanos = channel.runScheduledPendingTasks()
        nextScheduledTaskDelay = if (nanos >= 0) nanos.nanoseconds else null
    }

    private fun drainOutbound(): List<DatagramPacket> =
        generateSequence { channel.readOutbound<DatagramPacket>() }
            .map {
                if (it.sender() == null) {
                    val packet = DatagramPacket(it.content().retain(), it.recipient(), channel.localAddress())
                    it.release()
                    packet
                } else {
                    it
                }
            }
            .toList()
}
