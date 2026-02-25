package io.libp2p.quicsim

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelId
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SimulatedDatagramNetwork(
    private val bandwidthPolicy: BandwidthPolicy = TokenBucketBandwidthPolicy()
) {

    private data class QueuedDatagram(
        val recipient: InetSocketAddress,
        val sender: InetSocketAddress,
        val payload: ByteBuf
    )

    private val channelsByAddress = linkedMapOf<InetSocketAddress, EmbeddedChannel>()
    private val queuedDatagrams = ArrayDeque<QueuedDatagram>()
    private var nextEphemeralPort = 43000
    private var simulatedMillis = 0L

    fun bindClientParent(bootstrap: Bootstrap, handler: ChannelHandler): CompletableFuture<Channel> {
        return bindClientParent(NodeBandwidth.UNLIMITED, bootstrap, handler)
    }

    fun bindClientParent(
        bandwidth: NodeBandwidth,
        bootstrap: Bootstrap,
        handler: ChannelHandler
    ): CompletableFuture<Channel> {
        val address = InetSocketAddress("127.0.0.1", nextEphemeralPort++)
        return bind(bandwidth, bootstrap, address, handler)
    }

    fun bindServerParent(bootstrap: Bootstrap, bindAddress: SocketAddress, handler: ChannelHandler): CompletableFuture<Channel> {
        return bindServerParent(NodeBandwidth.UNLIMITED, bootstrap, bindAddress, handler)
    }

    fun bindServerParent(
        bandwidth: NodeBandwidth,
        bootstrap: Bootstrap,
        bindAddress: SocketAddress,
        handler: ChannelHandler
    ): CompletableFuture<Channel> {
        return bind(bandwidth, bootstrap, bindAddress as InetSocketAddress, handler)
    }

    private fun bind(
        bandwidth: NodeBandwidth,
        @Suppress("UNUSED_PARAMETER") bootstrap: Bootstrap,
        address: InetSocketAddress,
        handler: ChannelHandler
    ): CompletableFuture<Channel> {
        val channel = SimDatagramChannel("sim-${address.port}", address, handler)
        channelsByAddress[address] = channel
        bandwidthPolicy.onBind(address, bandwidth, simulatedMillis)
        channel.bind(address).syncUninterruptibly()
        channel.closeFuture().addListener {
            channelsByAddress.remove(address)
            bandwidthPolicy.onUnbind(address)
        }
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
        simulatedMillis += unit.toMillis(amount)
        bandwidthPolicy.onTimeAdvanced(simulatedMillis)
        channelsByAddress.values.forEach { it.advanceTimeBy(amount, unit) }
    }

    private fun pumpPackets() {
        var deliveredAny: Boolean
        do {
            drainOutboundToQueue()
            deliveredAny = deliverQueuedDatagrams()
        } while (deliveredAny)
    }

    private fun drainOutboundToQueue() {
        channelsByAddress.forEach { (localAddress, fromChannel) ->
            while (true) {
                val msg = fromChannel.readOutbound<Any>() ?: break
                if (msg is DatagramPacket) {
                    val recipient = msg.recipient() ?: (fromChannel.remoteAddress() as? InetSocketAddress)
                    val sender = msg.sender() ?: localAddress
                    if (recipient != null && channelsByAddress.containsKey(recipient) && msg.content().refCnt() > 0) {
                        val payload = msg.content().retain()
                        queuedDatagrams.addLast(
                            QueuedDatagram(
                                recipient = recipient,
                                sender = sender,
                                payload = payload
                            )
                        )
                    }
                    ReferenceCountUtil.safeRelease(msg)
                } else {
                    ReferenceCountUtil.safeRelease(msg)
                }
            }
        }
    }

    private fun deliverQueuedDatagrams(): Boolean {
        if (queuedDatagrams.isEmpty()) return false

        var deliveredAny = false
        val queueSize = queuedDatagrams.size
        repeat(queueSize) {
            val queued = queuedDatagrams.removeFirst()
            val senderExists = channelsByAddress.containsKey(queued.sender)
            val destination = channelsByAddress[queued.recipient]

            if (!senderExists || destination == null) {
                ReferenceCountUtil.safeRelease(queued.payload)
                return@repeat
            }

            val payloadBytes = queued.payload.readableBytes()
            if (!bandwidthPolicy.tryConsume(queued.sender, queued.recipient, payloadBytes, simulatedMillis)) {
                queuedDatagrams.addLast(queued)
                return@repeat
            }

            destination.writeInbound(DatagramPacket(queued.payload, queued.recipient, queued.sender))
            deliveredAny = true
        }
        return deliveredAny
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
