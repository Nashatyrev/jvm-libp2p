package io.libp2p.quicsim

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelId
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SimulatedDatagramNetwork {
    private val channelsByAddress = linkedMapOf<InetSocketAddress, EmbeddedChannel>()
    private var nextEphemeralPort = 43000

    fun bindClientParent(bootstrap: Bootstrap, handler: ChannelHandler): CompletableFuture<Channel> {
        val address = InetSocketAddress("127.0.0.1", nextEphemeralPort++)
        return bind(bootstrap, address, handler)
    }

    fun bindServerParent(bootstrap: Bootstrap, bindAddress: SocketAddress, handler: ChannelHandler): CompletableFuture<Channel> {
        return bind(bootstrap, bindAddress as InetSocketAddress, handler)
    }

    private fun bind(
        @Suppress("UNUSED_PARAMETER") bootstrap: Bootstrap,
        address: InetSocketAddress,
        handler: ChannelHandler
    ): CompletableFuture<Channel> {
        val channel = SimDatagramChannel("sim-${address.port}", address, handler)
        channelsByAddress[address] = channel
        channel.bind(address).syncUninterruptibly()
        return CompletableFuture.completedFuture(channel)
    }

    fun runSteps(steps: Int) {
        repeat(steps) {
            channelsByAddress.values.forEach { channel ->
                channel.runPendingTasks()
                channel.runScheduledPendingTasks()
            }
            pumpPackets()
        }
    }

    fun runUntil(done: () -> Boolean, timeout: Duration, stepCount: Int = 100) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!done() && System.nanoTime() < deadline) {
            runSteps(stepCount)
        }
        check(done()) { "Condition was not reached in simulated protocol loop" }
    }

    fun advanceTimeBy(amount: Long, unit: TimeUnit) {
        channelsByAddress.values.forEach { it.advanceTimeBy(amount, unit) }
    }

    private fun pumpPackets() {
        var deliveredAny: Boolean
        do {
            deliveredAny = false
            channelsByAddress.values.forEach { fromChannel ->
                while (true) {
                    val msg = fromChannel.readOutbound<Any>() ?: break
                    if (msg is DatagramPacket) {
                        val recipient = msg.recipient() ?: (fromChannel.remoteAddress() as? InetSocketAddress)
                        val sender = msg.sender() ?: (fromChannel.localAddress() as? InetSocketAddress)
                        if (recipient != null && sender != null) {
                            val destination = channelsByAddress[recipient]
                            if (destination != null) {
                                val inboundMsg = DatagramPacket(msg.content().retain(), recipient, sender)
                                destination.writeInbound(inboundMsg)
                                deliveredAny = true
                            }
                        }
                        ReferenceCountUtil.release(msg)
                    } else {
                        ReferenceCountUtil.release(msg)
                    }
                }
            }
        } while (deliveredAny)
    }

    private class SimDatagramChannel(
        id: String,
        private val localAddress: InetSocketAddress,
        handler: ChannelHandler
    ) : EmbeddedChannel(SimChannelId(id), handler) {
        override fun localAddress(): SocketAddress = localAddress
        override fun remoteAddress(): SocketAddress? = null
    }

    private class SimChannelId(private val id: String) : ChannelId {
        override fun asShortText(): String = id
        override fun asLongText(): String = id
        override fun compareTo(other: ChannelId): Int = asLongText().compareTo(other.asLongText())
    }
}
