package io.libp2p.transport.quic

import io.libp2p.core.Connection
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.transport.Transport
import io.libp2p.protocol.PingBinding
import io.libp2p.protocol.PingController
import io.libp2p.protocol.PingProtocol
import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
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
import java.util.function.BiFunction

class QuicTransportSimIdleTimeoutTest {

    @Test
    fun `quic transport closes idle connection when embedded time is advanced`() {
        val serverKey = generateKeyPair(KeyType.ED25519).first
        val clientKey = generateKeyPair(KeyType.ED25519).first
        val serverPeerId = PeerId.fromPubKey(serverKey.publicKey())
        val receivedByServer = CompletableFuture<ByteArray>()
        val pingProtocol = pingProtocol(receivedByServer)
        val pingBinding = PingBinding(pingProtocol)

        val network = EmbeddedDatagramNetwork()
        val serverTransport = QuicTransport(
            serverKey,
            "ECDSA",
            listOf(pingBinding),
            datagramChannelFactory = network.newDatagramFactory("server")
        )
        val clientTransport = QuicTransport(
            clientKey,
            "ECDSA",
            listOf(pingBinding),
            datagramChannelFactory = network.newDatagramFactory("client")
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

            val streamPromise = clientConn.muxerSession().createStream(pingBinding)
            network.runUntil({ streamPromise.stream.isDone && streamPromise.controller.isDone }, Duration.ofSeconds(5))
            streamPromise.stream.get(1, TimeUnit.SECONDS)
            val pingController = streamPromise.controller.get(1, TimeUnit.SECONDS) as PingController
            pingController.ping()
            network.runSteps(200)
            network.runUntil({ receivedByServer.isDone }, Duration.ofSeconds(5))
            val received = receivedByServer.get(1, TimeUnit.SECONDS)
            assertTrue(received.isNotEmpty(), "Expected server to receive ping payload")

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

    @Test
    fun `host builder based quic peers exchange packet and close on idle timeout`() {
        println("=== TEST START: host builder based quic peers exchange packet and close on idle timeout ===")
        val receivedByServer = CompletableFuture<ByteArray>()
        val pingBinding = PingBinding(pingProtocol(receivedByServer))
        val network = EmbeddedDatagramNetwork()
        val serverConnFuture = CompletableFuture<Connection>()
        val transportFactory = BiFunction<PrivKey, List<ProtocolBinding<*>>, Transport> { key, protocols ->
            QuicTransport(
                key,
                "ECDSA",
                protocols,
                datagramChannelFactory = network.newDatagramFactory("host-${PeerId.fromPubKey(key.publicKey())}")
            )
        }

        val serverHost = HostBuilder(HostBuilder.DefaultMode.None)
            .keyType(KeyType.ED25519)
            .secureTransport(transportFactory)
            .protocol(pingBinding)
            .listen("/ip4/127.0.0.1/udp/41021/quic-v1")
            .builderModifier { b ->
                b.connectionHandlers.add(ConnectionHandler { serverConnFuture.complete(it) })
            }
            .build()

        val clientHost = HostBuilder(HostBuilder.DefaultMode.None)
            .keyType(KeyType.ED25519)
            .secureTransport(transportFactory)
            .protocol(pingBinding)
            .build()

        try {
            println("Starting server and client hosts")
            val serverStart = serverHost.start()
            val clientStart = clientHost.start()
            network.runUntil({ serverStart.isDone && clientStart.isDone }, Duration.ofSeconds(5))
            serverStart.get(1, TimeUnit.SECONDS)
            clientStart.get(1, TimeUnit.SECONDS)
            println("Hosts started: clientPeer=${clientHost.peerId} serverPeer=${serverHost.peerId}")

            println("Connecting client to server over QUIC multiaddr")
            val connectFuture = clientHost.network.connect(
                serverHost.peerId,
                Multiaddr("/ip4/127.0.0.1/udp/41021/quic-v1")
            )
            network.runUntil({ connectFuture.isDone && serverConnFuture.isDone }, Duration.ofSeconds(5))
            val clientConn = connectFuture.get(1, TimeUnit.SECONDS)
            val serverConn = serverConnFuture.get(1, TimeUnit.SECONDS)
            println("Connected: clientLocal=${clientConn.localAddress()} clientRemote=${clientConn.remoteAddress()}")
            println("Connected: serverLocal=${serverConn.localAddress()} serverRemote=${serverConn.remoteAddress()}")

            println("Creating stream via muxerSession().createStream(...)")
            val streamPromise = clientConn.muxerSession().createStream(pingBinding)
            network.runUntil({ streamPromise.stream.isDone && streamPromise.controller.isDone }, Duration.ofSeconds(5))
            val stream = streamPromise.stream.get(1, TimeUnit.SECONDS)
            val pingController = streamPromise.controller.get(1, TimeUnit.SECONDS) as PingController
            println("Stream created: initiator=${stream.isInitiator}, protocol=/ipfs/ping/1.0.0")

            println("Sending single ping packet over stream")
            pingController.ping()
            network.runSteps(200)
            network.runUntil({ receivedByServer.isDone }, Duration.ofSeconds(5))
            val received = receivedByServer.get(1, TimeUnit.SECONDS)
            println("Server received packet bytes=${received.contentToString()}")
            assertTrue(received.isNotEmpty(), "Expected server to receive ping payload")

            println("Advancing simulated time until idle timeout closes both connections")
            var elapsedSeconds = 0
            while (elapsedSeconds < 180 &&
                (!clientConn.closeFuture().isDone || !serverConn.closeFuture().isDone)
            ) {
                network.advanceTimeBy(10, TimeUnit.SECONDS)
                network.runSteps(300)
                elapsedSeconds += 10
                println(
                    "t=${elapsedSeconds}s " +
                        "clientClosed=${clientConn.closeFuture().isDone} " +
                        "serverClosed=${serverConn.closeFuture().isDone}"
                )
            }

            println(
                "Final close status after simulated t=${elapsedSeconds}s: " +
                    "client=${clientConn.closeFuture().isDone}, server=${serverConn.closeFuture().isDone}"
            )
            assertTrue(clientConn.closeFuture().isDone, "Client connection should close after idle timeout")
            assertTrue(serverConn.closeFuture().isDone, "Server connection should close after idle timeout")
        } finally {
            println("Stopping hosts")
            clientHost.stop().get(5, TimeUnit.SECONDS)
            println("Client stopped")
            serverHost.stop().get(5, TimeUnit.SECONDS)
            println("Server stopped")
            println("=== TEST END: host builder based quic peers exchange packet and close on idle timeout ===")
        }
    }

    private fun pingProtocol(receivedByServer: CompletableFuture<ByteArray>): PingProtocol =
        object : PingProtocol() {
            override fun onStartResponder(stream: Stream): CompletableFuture<PingController> {
                val handler = object : PingResponder() {
                    override fun onMessage(stream: Stream, msg: ByteBuf) {
                        if (!receivedByServer.isDone) {
                            val bytes = ByteArray(msg.readableBytes())
                            msg.getBytes(msg.readerIndex(), bytes)
                            receivedByServer.complete(bytes)
                        }
                        super.onMessage(stream, msg)
                    }
                }
                stream.pushHandler(handler)
                return CompletableFuture.completedFuture(handler)
            }
        }

    private class EmbeddedDatagramNetwork {
        private lateinit var serverParent: SimDatagramChannel
        private lateinit var clientParent: SimDatagramChannel
        private lateinit var serverAddress: InetSocketAddress
        private lateinit var clientAddress: InetSocketAddress
        private var nextClientPort = 42000

        fun bindServerParent(
            bindAddress: SocketAddress,
            handler: ChannelHandler
        ): CompletableFuture<Channel> {
            serverAddress = bindAddress as InetSocketAddress
            serverParent = SimDatagramChannel("sim-server", serverAddress, handler)
            serverParent.bind(serverAddress).syncUninterruptibly()
            return CompletableFuture.completedFuture(serverParent)
        }

        fun bindClientParent(
            handler: ChannelHandler
        ): CompletableFuture<Channel> {
            clientAddress = InetSocketAddress("127.0.0.1", nextClientPort++)
            clientParent = SimDatagramChannel("sim-client", clientAddress, handler)
            clientParent.bind(clientAddress).syncUninterruptibly()
            return CompletableFuture.completedFuture(clientParent)
        }

        fun newDatagramFactory(name: String): DatagramChannelFactory = object : DatagramChannelFactory {
            override fun createClientChannel(handler: ChannelHandler): CompletableFuture<Channel> =
                bindClientParent(handler)

            override fun createServerChannel(
                bindAddress: SocketAddress,
                handler: ChannelHandler
            ): CompletableFuture<Channel> = bindServerParent(bindAddress, handler)

            override fun shutdown() = CompletableFuture.completedFuture(Unit)

            override fun toString(): String = "EmbeddedDatagramFactory($name)"
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
        override fun localAddress(): InetSocketAddress = local
        override fun remoteAddress(): InetSocketAddress? = null
    }

    private class SimChannelId(private val id: String) : ChannelId {
        override fun asShortText(): String = id
        override fun asLongText(): String = id
        override fun compareTo(other: ChannelId): Int = asLongText().compareTo(other.asLongText())
    }
}
