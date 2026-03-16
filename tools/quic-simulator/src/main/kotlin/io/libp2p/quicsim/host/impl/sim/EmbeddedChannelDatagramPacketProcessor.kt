package io.libp2p.quicsim.host.impl.sim

import io.libp2p.quicsim.core.PacketProcessor
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Adapts [SimDatagramChannel] to [PacketProcessor] contract where packets are exchanged as Netty [DatagramPacket].
 */
class EmbeddedChannelDatagramPacketProcessor(
    val channel: SimDatagramChannel
) : PacketProcessor<DatagramPacket> {
    private var nextScheduledTaskDelay: Duration? = null

    override fun deliver(inboundData: List<DatagramPacket>): List<DatagramPacket> {
        inboundData.forEach {
            channel.writeInbound(it)
        }
        runTasksAtCurrentTime()
        return drainOutbound()
    }

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
        channel.advanceTimeBy(advanceDuration.inWholeNanoseconds, TimeUnit.NANOSECONDS)
        runTasksAtCurrentTime()
    }

    override fun nextTaskDuration(): Duration? =
        if (channel.hasPendingTasks()) Duration.ZERO else nextScheduledTaskDelay

    private fun runTasksAtCurrentTime() {
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
