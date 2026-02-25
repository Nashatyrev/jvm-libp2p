package io.libp2p.quicsim

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
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
import kotlin.math.max
import kotlin.math.min

class SimulatedDatagramNetwork {

    data class NodeBandwidth(
        val inboundBytesPerSecond: Long = Long.MAX_VALUE,
        val outboundBytesPerSecond: Long = Long.MAX_VALUE
    ) {
        init {
            require(inboundBytesPerSecond > 0) { "inboundBytesPerSecond must be > 0" }
            require(outboundBytesPerSecond > 0) { "outboundBytesPerSecond must be > 0" }
        }

        companion object {
            val UNLIMITED = NodeBandwidth()
        }
    }

    private data class ChannelState(
        val inboundBytesPerSecond: Long,
        val outboundBytesPerSecond: Long,
        val inboundCapacity: Double,
        val outboundCapacity: Double,
        var inboundTokens: Double,
        var outboundTokens: Double,
        var lastRefillMillis: Long
    )

    private data class QueuedDatagram(
        val recipient: InetSocketAddress,
        val sender: InetSocketAddress,
        val payload: ByteArray
    )

    private val channelsByAddress = linkedMapOf<InetSocketAddress, EmbeddedChannel>()
    private val statesByAddress = linkedMapOf<InetSocketAddress, ChannelState>()
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
        statesByAddress[address] = ChannelState(
            inboundBytesPerSecond = bandwidth.inboundBytesPerSecond,
            outboundBytesPerSecond = bandwidth.outboundBytesPerSecond,
            inboundCapacity = capacityFor(bandwidth.inboundBytesPerSecond),
            outboundCapacity = capacityFor(bandwidth.outboundBytesPerSecond),
            inboundTokens = initialTokensFor(bandwidth.inboundBytesPerSecond),
            outboundTokens = initialTokensFor(bandwidth.outboundBytesPerSecond),
            lastRefillMillis = simulatedMillis
        )
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
        simulatedMillis += unit.toMillis(amount)
        channelsByAddress.values.forEach { it.advanceTimeBy(amount, unit) }
    }

    fun updateBandwidth(address: InetSocketAddress, bandwidth: NodeBandwidth) {
        val existingState = statesByAddress[address]
            ?: throw IllegalArgumentException("No simulated datagram channel bound to address $address")
        statesByAddress[address] = existingState.copy(
            inboundBytesPerSecond = bandwidth.inboundBytesPerSecond,
            outboundBytesPerSecond = bandwidth.outboundBytesPerSecond,
            inboundCapacity = capacityFor(bandwidth.inboundBytesPerSecond),
            outboundCapacity = capacityFor(bandwidth.outboundBytesPerSecond),
            inboundTokens = initialTokensFor(bandwidth.inboundBytesPerSecond),
            outboundTokens = initialTokensFor(bandwidth.outboundBytesPerSecond),
            lastRefillMillis = simulatedMillis
        )
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
                        val payload = ByteArray(msg.content().readableBytes())
                        msg.content().getBytes(msg.content().readerIndex(), payload)
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
            val senderState = statesByAddress[queued.sender]
            val recipientState = statesByAddress[queued.recipient]
            val destination = channelsByAddress[queued.recipient]

            if (senderState == null || recipientState == null || destination == null) {
                return@repeat
            }

            refillTokens(senderState)
            refillTokens(recipientState)

            if (!hasEnoughTokens(senderState.outboundTokens, senderState.outboundBytesPerSecond, queued.payload.size) ||
                !hasEnoughTokens(recipientState.inboundTokens, recipientState.inboundBytesPerSecond, queued.payload.size)
            ) {
                queuedDatagrams.addLast(queued)
                return@repeat
            }

            senderState.outboundTokens =
                consumeTokens(senderState.outboundTokens, senderState.outboundBytesPerSecond, queued.payload.size)
            recipientState.inboundTokens =
                consumeTokens(recipientState.inboundTokens, recipientState.inboundBytesPerSecond, queued.payload.size)

            destination.writeInbound(DatagramPacket(Unpooled.wrappedBuffer(queued.payload), queued.recipient, queued.sender))
            deliveredAny = true
        }
        return deliveredAny
    }

    private fun refillTokens(state: ChannelState) {
        val elapsedMillis = simulatedMillis - state.lastRefillMillis
        if (elapsedMillis <= 0) return

        state.inboundTokens = refillTokenBucket(
            tokens = state.inboundTokens,
            bytesPerSecond = state.inboundBytesPerSecond,
            capacity = state.inboundCapacity,
            elapsedMillis = elapsedMillis
        )
        state.outboundTokens = refillTokenBucket(
            tokens = state.outboundTokens,
            bytesPerSecond = state.outboundBytesPerSecond,
            capacity = state.outboundCapacity,
            elapsedMillis = elapsedMillis
        )
        state.lastRefillMillis = simulatedMillis
    }

    private fun refillTokenBucket(tokens: Double, bytesPerSecond: Long, capacity: Double, elapsedMillis: Long): Double {
        if (bytesPerSecond == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        val replenished = tokens + bytesPerSecond.toDouble() * elapsedMillis.toDouble() / 1000.0
        return min(capacity, replenished)
    }

    private fun hasEnoughTokens(tokens: Double, bytesPerSecond: Long, bytes: Int): Boolean {
        if (bytesPerSecond == Long.MAX_VALUE) return true
        return tokens + 1e-9 >= bytes
    }

    private fun consumeTokens(tokens: Double, bytesPerSecond: Long, bytes: Int): Double {
        if (bytesPerSecond == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        return max(0.0, tokens - bytes.toDouble())
    }

    private fun capacityFor(bytesPerSecond: Long): Double {
        if (bytesPerSecond == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        return max(bytesPerSecond.toDouble(), 65535.0)
    }

    private fun initialTokensFor(bytesPerSecond: Long): Double {
        if (bytesPerSecond == Long.MAX_VALUE) return Double.POSITIVE_INFINITY
        return bytesPerSecond.toDouble()
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
