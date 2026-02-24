package sample

import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler
import io.netty.incubator.codec.quic.QuicChannel
import io.netty.incubator.codec.quic.QuicClientCodecBuilder
import io.netty.incubator.codec.quic.QuicServerCodecBuilder
import io.netty.incubator.codec.quic.QuicSslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util.ReferenceCountUtil
import io.netty.util.concurrent.ImmediateExecutor
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class EmbeddedQuicTimeoutTest {

    @Test
    fun connectEmbeddedClientAndServerThenTimeoutOnSimulatedTime() {
        println("=== TEST START: connectEmbeddedClientAndServerThenTimeoutOnSimulatedTime ===")
        val cert = SelfSignedCertificate()
        println("Created self-signed certificate")

        val serverSsl = QuicSslContextBuilder
            .forServer(cert.privateKey(), null, cert.certificate())
            .applicationProtocols("hq-29")
            .build()
        val clientSsl = QuicSslContextBuilder
            .forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols("hq-29")
            .build()

        val serverQuicRef = AtomicReference<QuicChannel>()
        val serverAddress = InetSocketAddress("127.0.0.1", 9999)
        val clientAddress = InetSocketAddress("127.0.0.1", 9998)
        println("Configured endpoints: client=$clientAddress server=$serverAddress")

        val serverParent = EmbeddedChannel(
            QuicServerCodecBuilder()
                .sslEngineProvider { q -> serverSsl.newEngine(q.alloc()) }
                .maxIdleTimeout(1, TimeUnit.MINUTES)
                .initialMaxData(1_000_000)
                .initialMaxStreamDataBidirectionalLocal(100_000)
                .initialMaxStreamDataBidirectionalRemote(100_000)
                .initialMaxStreamDataUnidirectional(100_000)
                .initialMaxStreamsBidirectional(10)
                .initialMaxStreamsUnidirectional(10)
                .activeMigration(false)
                .sslTaskExecutor(ImmediateExecutor.INSTANCE)
                .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                .handler(object : ChannelInboundHandlerAdapter() {
                    override fun channelActive(ctx: io.netty.channel.ChannelHandlerContext) {
                        val ch = ctx.channel()
                        if (ch is QuicChannel) {
                            println("Server QUIC child became active: id=${ch.id()} local=${ch.localAddress()} remote=${ch.remoteAddress()}")
                            serverQuicRef.set(ch)
                        }
                        ctx.fireChannelActive()
                    }
                })
                .streamHandler(ChannelInboundHandlerAdapter())
                .build()
        )

        val clientParent = EmbeddedChannel(
            QuicClientCodecBuilder()
                .sslEngineProvider { q -> clientSsl.newEngine(q.alloc()) }
                .maxIdleTimeout(1, TimeUnit.MINUTES)
                .initialMaxData(1_000_000)
                .initialMaxStreamDataBidirectionalLocal(100_000)
                .initialMaxStreamDataBidirectionalRemote(100_000)
                .initialMaxStreamDataUnidirectional(100_000)
                .initialMaxStreamsBidirectional(10)
                .initialMaxStreamsUnidirectional(10)
                .activeMigration(false)
                .sslTaskExecutor(ImmediateExecutor.INSTANCE)
                .build()
        )

        serverParent.bind(serverAddress).syncUninterruptibly()
        clientParent.bind(clientAddress).syncUninterruptibly()
        println("Bound embedded parents")

        val connectFuture = QuicChannel.newBootstrap(clientParent)
            .handler(ChannelInboundHandlerAdapter())
            .streamHandler(ChannelInboundHandlerAdapter())
            .localAddress(clientAddress)
            .remoteAddress(serverAddress)
            .connect()
        println("Started client QUIC connect")

        runProtocolUntil(
            { connectFuture.isDone },
            clientParent,
            serverParent,
            clientAddress,
            serverAddress
        )
        println("Connect future completed: success=${connectFuture.isSuccess} cause=${connectFuture.cause()}")
        if (!connectFuture.isSuccess) {
            throw AssertionError("QUIC connect failed", connectFuture.cause())
        }

        runProtocolUntil(
            { serverQuicRef.get() != null },
            clientParent,
            serverParent,
            clientAddress,
            serverAddress
        )
        println("Server QUIC reference observed")

        val clientQuic = connectFuture.getNow()
        val serverQuic = serverQuicRef.get()
        println("Client QUIC: id=${clientQuic.id()} local=${clientQuic.localAddress()} remote=${clientQuic.remoteAddress()}")
        println("Server QUIC: id=${serverQuic.id()} local=${serverQuic.localAddress()} remote=${serverQuic.remoteAddress()}")
        assertNotNull(clientQuic)
        assertNotNull(serverQuic)

        // No packets exchanged, verify timeout does not trigger at 59s.
        println("Advancing embedded time by 59 seconds on both parents")
        clientParent.advanceTimeBy(59, TimeUnit.SECONDS)
        serverParent.advanceTimeBy(59, TimeUnit.SECONDS)
        runProtocolSteps(300, clientParent, serverParent, clientAddress, serverAddress)
        println(
            "After 59s close futures: client=${clientQuic.closeFuture().isDone} " +
                "server=${serverQuic.closeFuture().isDone}"
        )
        assertTrue(!clientQuic.closeFuture().isDone)
        assertTrue(!serverQuic.closeFuture().isDone)

        // Move past 60s threshold and verify timeout closure.
        var elapsedSeconds = 59
        var firstCloseLogged = false
        while (elapsedSeconds < 180 &&
            !(clientQuic.closeFuture().isDone && serverQuic.closeFuture().isDone)) {
            clientParent.advanceTimeBy(10, TimeUnit.SECONDS)
            serverParent.advanceTimeBy(10, TimeUnit.SECONDS)
            elapsedSeconds += 10
            val (c2s, s2c) = runProtocolSteps(300, clientParent, serverParent, clientAddress, serverAddress)
            val clientClosed = clientQuic.closeFuture().isDone
            val serverClosed = serverQuic.closeFuture().isDone
            println(
                "t=${elapsedSeconds}s clientClosed=$clientClosed serverClosed=$serverClosed " +
                    "pumped(c->s=$c2s, s->c=$s2c)"
            )
            if (!firstCloseLogged && (clientClosed || serverClosed)) {
                firstCloseLogged = true
                println(
                    "First close observed at t=${elapsedSeconds}s " +
                        "(clientClosed=$clientClosed, serverClosed=$serverClosed)"
                )
            }
        }
        println("Observed close state at elapsedSeconds=$elapsedSeconds")
        assertTrue(elapsedSeconds >= 60)
        println(
            "Close futures done: client=${clientQuic.closeFuture().isDone} " +
                "server=${serverQuic.closeFuture().isDone}"
        )

        assertTrue(clientQuic.closeFuture().isDone)
        assertTrue(serverQuic.closeFuture().isDone)

        println("Releasing channels")
        clientParent.finishAndReleaseAll()
        serverParent.finishAndReleaseAll()
        cert.delete()
        println("=== TEST END: success ===")
    }

    private fun runProtocolUntil(
        done: () -> Boolean,
        clientParent: EmbeddedChannel,
        serverParent: EmbeddedChannel,
        clientAddress: InetSocketAddress,
        serverAddress: InetSocketAddress
    ) {
        repeat(5000) {
            if (it % 100 == 0) {
                println(
                    "runProtocolUntil iteration=$it clientOut=${clientParent.outboundMessages().size} " +
                        "serverOut=${serverParent.outboundMessages().size}"
                )
            }
            clientParent.runPendingTasks()
            serverParent.runPendingTasks()
            clientParent.runScheduledPendingTasks()
            serverParent.runScheduledPendingTasks()
            val c2s = pump(clientParent, serverParent, clientAddress, serverAddress)
            val s2c = pump(serverParent, clientParent, serverAddress, clientAddress)
            if (c2s != 0 || s2c != 0) {
                println("Pumped packets: client->server=$c2s server->client=$s2c")
            }
            if (done()) {
                println("runProtocolUntil condition reached at iteration=$it")
                return
            }
        }
        throw IllegalStateException(
            "Condition not reached in protocol loop. " +
                "clientOut=${clientParent.outboundMessages().size}, " +
                "serverOut=${serverParent.outboundMessages().size}"
        )
    }

    private fun runProtocolSteps(
        steps: Int,
        clientParent: EmbeddedChannel,
        serverParent: EmbeddedChannel,
        clientAddress: InetSocketAddress,
        serverAddress: InetSocketAddress
    ): Pair<Int, Int> {
        var totalC2S = 0
        var totalS2C = 0
        repeat(steps) {
            clientParent.runPendingTasks()
            serverParent.runPendingTasks()
            clientParent.runScheduledPendingTasks()
            serverParent.runScheduledPendingTasks()
            val c2s = pump(clientParent, serverParent, clientAddress, serverAddress)
            val s2c = pump(serverParent, clientParent, serverAddress, clientAddress)
            totalC2S += c2s
            totalS2C += s2c
            if (c2s != 0 || s2c != 0) {
                println("runProtocolSteps pumped: client->server=$c2s server->client=$s2c")
            }
        }
        return totalC2S to totalS2C
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
                DatagramPacket(
                    msg.content().retain(),
                    recipient,
                    sender
                ).also { ReferenceCountUtil.release(msg) }
            } else {
                msg
            }
            to.writeInbound(inboundMsg)
            count++
        }
    }
}
