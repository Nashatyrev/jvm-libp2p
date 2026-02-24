package io.libp2p.transport.quic

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelId
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class QuicTransportSimIdleTimeoutTest {

    @Test
    fun `quic transport closes idle connection when embedded time is advanced`() {
        val serverKey = generateKeyPair(KeyType.ED25519).first
        val clientKey = generateKeyPair(KeyType.ED25519).first
        val serverPeerId = PeerId.fromPubKey(serverKey.publicKey())
        val receivedByServer = CompletableFuture<ByteArray>()
        val testProtocol = ProtocolBinding.createSimple<Unit>("/test/sim/1.0.0") { ch ->
            val stream = ch as Stream
            if (!stream.isInitiator) {
                stream.pushHandler(
                    object : ChannelInboundHandlerAdapter() {
                        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
                            try {
                                if (msg is ByteBuf && !receivedByServer.isDone) {
                                    val bytes = ByteArray(msg.readableBytes())
                                    msg.getBytes(msg.readerIndex(), bytes)
                                    receivedByServer.complete(bytes)
                                }
                            } finally {
                                ReferenceCountUtil.release(msg)
                            }
                        }
                    }
                )
            }
            CompletableFuture.completedFuture(Unit)
        }

        val network = EmbeddedDatagramNetwork()
        val serverTransport = QuicTransport(
            serverKey,
            "ECDSA",
            listOf(testProtocol),
            network::bindClientParent,
            network::bindServerParent
        )
        val clientTransport = QuicTransport(
            clientKey,
            "ECDSA",
            listOf(testProtocol),
            network::bindClientParent,
            network::bindServerParent
        )

        try {
            val serverListen = Multiaddr("/ip4/127.0.0.1/udp/41001/quic-v1")
            val serverConnFuture = CompletableFuture<Connection>()
            val listenFuture = serverTransport.listen(serverListen, ConnectionHandler { serverConnFuture.complete(it) })
            network.runUntil({ listenFuture.isDone }, Duration.ofSeconds(5))
            listenFuture.get(1, TimeUnit.SECONDS)

            val dialAddr = Multiaddr("/ip4/127.0.0.1/udp/41001/quic-v1/p2p/$serverPeerId")
            val dialFuture = clientTransport.dial(dialAddr, ConnectionHandler { })
            network.runUntil({ dialFuture.isDone && serverConnFuture.isDone }, Duration.ofSeconds(5))

            val clientConn = dialFuture.get(1, TimeUnit.SECONDS)
            val serverConn = serverConnFuture.get(1, TimeUnit.SECONDS)

            val streamPromise = clientConn.muxerSession().createStream(testProtocol)
            network.runUntil({ streamPromise.stream.isDone && streamPromise.controller.isDone }, Duration.ofSeconds(5))
            val stream = streamPromise.stream.get(1, TimeUnit.SECONDS)
            streamPromise.controller.get(1, TimeUnit.SECONDS)

            stream.writeAndFlush(Unpooled.wrappedBuffer(byteArrayOf(1)))
            network.runSteps(200)
            network.runUntil({ receivedByServer.isDone }, Duration.ofSeconds(5))
            val received = receivedByServer.get(1, TimeUnit.SECONDS)
            assertTrue(received.contentEquals(byteArrayOf(1)), "Expected server to receive the sent packet")

            var elapsedSeconds = 0
            while (elapsedSeconds < 180 &&
                (!clientConn.closeFuture().isDone || !serverConn.closeFuture().isDone)
            ) {
                network.advanceTimeBy(10, TimeUnit.SECONDS)
                network.runSteps(300)
                elapsedSeconds += 10
            }

            assertTrue(clientConn.closeFuture().isDone, "Client connection should close after idle timeout")
            assertTrue(serverConn.closeFuture().isDone, "Server connection should close after idle timeout")
        } finally {
            clientTransport.close().get(5, TimeUnit.SECONDS)
            serverTransport.close().get(5, TimeUnit.SECONDS)
        }
    }

    private class EmbeddedDatagramNetwork {
        private lateinit var serverParent: EmbeddedChannel
        private lateinit var clientParent: EmbeddedChannel
        private lateinit var serverAddress: InetSocketAddress
        private lateinit var clientAddress: InetSocketAddress
        private var nextClientPort = 42000

        fun bindServerParent(
            @Suppress("UNUSED_PARAMETER") bootstrap: Bootstrap,
            bindAddress: SocketAddress,
            handler: ChannelHandler
        ): CompletableFuture<Channel> {
            serverAddress = bindAddress as InetSocketAddress
            serverParent = SimDatagramChannel("sim-server", serverAddress, handler)
            serverParent.bind(serverAddress).syncUninterruptibly()
            return CompletableFuture.completedFuture(serverParent)
        }

        fun bindClientParent(
            @Suppress("UNUSED_PARAMETER") bootstrap: Bootstrap,
            handler: ChannelHandler
        ): CompletableFuture<Channel> {
            clientAddress = InetSocketAddress("127.0.0.1", nextClientPort++)
            clientParent = SimDatagramChannel("sim-client", clientAddress, handler)
            clientParent.bind(clientAddress).syncUninterruptibly()
            return CompletableFuture.completedFuture(clientParent)
        }

        fun advanceTimeBy(amount: Long, unit: TimeUnit) {
            if (::clientParent.isInitialized) {
                clientParent.advanceTimeBy(amount, unit)
            }
            if (::serverParent.isInitialized) {
                serverParent.advanceTimeBy(amount, unit)
            }
        }

        fun runUntil(done: () -> Boolean, timeout: Duration) {
            val deadlineNanos = System.nanoTime() + timeout.toNanos()
            while (!done() && System.nanoTime() < deadlineNanos) {
                runSteps(1)
            }
            check(done()) { "Condition was not reached in simulated protocol loop" }
        }

        fun runSteps(steps: Int) {
            repeat(steps) {
                if (::clientParent.isInitialized) {
                    clientParent.runPendingTasks()
                    clientParent.runScheduledPendingTasks()
                }
                if (::serverParent.isInitialized) {
                    serverParent.runPendingTasks()
                    serverParent.runScheduledPendingTasks()
                }
                if (::clientParent.isInitialized && ::serverParent.isInitialized) {
                    pump(clientParent, serverParent, clientAddress, serverAddress)
                    pump(serverParent, clientParent, serverAddress, clientAddress)
                }
            }
        }

        private fun pump(
            from: EmbeddedChannel,
            to: EmbeddedChannel,
            sender: InetSocketAddress,
            recipient: InetSocketAddress
        ): Int {
            var count = 0
            while (true) {
                val msg = from.readOutbound<Any>() ?: return count
                val inboundMsg = if (msg is DatagramPacket) {
                    DatagramPacket(msg.content().retain(), recipient, sender).also {
                        ReferenceCountUtil.release(msg)
                    }
                } else {
                    msg
                }
                to.writeInbound(inboundMsg)
                count++
            }
        }
    }

    private class SimDatagramChannel(
        id: String,
        private val local: InetSocketAddress,
        handler: ChannelHandler
    ) : EmbeddedChannel(SimChannelId(id), handler) {
        override fun localAddress(): SocketAddress = local
        override fun remoteAddress(): SocketAddress? = null
    }

    private class SimChannelId(private val id: String) : ChannelId {
        override fun asShortText(): String = id
        override fun asLongText(): String = id
        override fun compareTo(other: ChannelId): Int = asLongText().compareTo(other.asLongText())
    }
}
