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

    override fun deliver(inboundData: List<DatagramPacket>): List<DatagramPacket> {
        inboundData.forEach {
            channel.writeInbound(it)
        }
        runPendingTasksOnly()
        return drainOutbound()
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
        // Time is provided by EmbeddedChannel custom Ticker (NettyTicker), so we should not call advanceTimeBy().
        runPendingAndScheduledTasks()
    }

    override fun nextTaskDuration(): Duration? =
        if (channel.hasPendingTasks()) Duration.ZERO else nextScheduledTaskDelay

    private fun runPendingTasksOnly() {
        channel.runPendingTasks()
    }

    private fun runPendingAndScheduledTasks() {
        channel.runPendingTasks()
        val nanos = channel.runScheduledPendingTasks()
        nextScheduledTaskDelay = if (nanos >= 0) nanos.nanoseconds else null
    }

    private fun drainOutbound(): List<DatagramPacket> =
        generateSequence { channel.readOutbound<DatagramPacket>() }
            .map {
                if (it.sender() == null) {
                    DatagramPacket(it.content(), it.recipient(), channel.localAddress())
                } else {
                    it
                }
            }
            .toList()
}
