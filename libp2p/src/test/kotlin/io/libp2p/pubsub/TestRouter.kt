package io.libp2p.pubsub

import io.libp2p.core.PeerId
import io.libp2p.core.crypto.KEY_TYPE
import io.libp2p.core.crypto.generateKeyPair
import io.libp2p.core.pubsub.RESULT_VALID
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.core.security.SecureChannel
import io.libp2p.etc.PROTOCOL
import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.util.netty.LoggingHandlerShort
import io.libp2p.etc.util.netty.nettyInitializer
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.tools.NullTransport
import io.libp2p.tools.TestChannel
import io.libp2p.tools.TestChannel.TestConnection
import io.libp2p.transport.implementation.ConnectionOverNetty
import io.libp2p.transport.implementation.StreamOverNetty
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

val cnt = AtomicInteger()
val idCnt = AtomicInteger()

class SemiduplexConnection(val conn1: TestConnection, val conn2: TestConnection) {
    val connections = listOf(conn1, conn2)
    fun setLatency(latency: Duration) {
        conn1.setLatency(latency)
        conn2.setLatency(latency)
    }
    fun disconnect() {
        conn1.disconnect()
        // conn2 should be dropped by the router
    }
}

class UncaughtExceptionHandler : ChannelInboundHandlerAdapter() {
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        System.err.println("${ctx.channel()}: Uncaught error")
        cause.printStackTrace()
    }
}

class TestRouter(
    val name: String = "" + cnt.getAndIncrement(),
    val router: PubsubRouterDebug
) {

    val inboundMessages = LinkedBlockingQueue<PubsubMessage>()
    var handlerValidationResult = RESULT_VALID
    val routerHandler: (PubsubMessage) -> CompletableFuture<ValidationResult> = {
        inboundMessages += it
        handlerValidationResult
    }

    var testExecutor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor() }

    var keyPair = generateKeyPair(KEY_TYPE.ECDSA)
    val peerId by lazy { PeerId.fromPubKey(keyPair.second) }
    val protocol = router.protocol.announceStr
    var pubsubLogWritesOnly = false
    var simTimeSupplier: CurrentTimeSupplier? = null

    init {
        router.initHandler(routerHandler)
    }

    private fun newChannel(
        channelName: String,
        remoteRouter: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null,
        initiator: Boolean
    ): TestChannel {

        val parentChannel = TestChannel("dummy-parent-channel", false)
        val connection =
            ConnectionOverNetty(parentChannel, NullTransport(), initiator)
        connection.setSecureSession(
            SecureChannel.Session(
                peerId, remoteRouter.peerId, remoteRouter.keyPair.second
            )
        )

        return TestChannel(
            channelName,
            initiator,
            nettyInitializer { ch ->
                wireLogs?.also { ch.channel.pipeline().addFirst(LoggingHandler(channelName, it)) }
                val stream1 = StreamOverNetty(ch.channel, connection, initiator)
                val pubsubLogHandler = pubsubLogs?.let { logLevel ->
                    val ret = LoggingHandlerShort(channelName, logLevel)
                    if (pubsubLogWritesOnly) {
                        ret.skipRead = true
                        ret.skipFlush = true
                    }
                    ret.simulateTimeSupplier = simTimeSupplier
                    ret.simulateTimeFormnat = SimpleDateFormat("ss.SSS")
                    ret
                }
                router.addPeerWithDebugHandler(stream1, pubsubLogHandler)
                ch.channel.pipeline().addLast(UncaughtExceptionHandler())
            }
        ).also {
            it.executor = testExecutor
        }
    }

    fun connect(
        another: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null
    ): TestConnection {

        val thisChannel = newChannel("[${idCnt.incrementAndGet()}]$name=>${another.name}", another, wireLogs, pubsubLogs, true)
        val anotherChannel = another.newChannel("[${idCnt.incrementAndGet()}]${another.name}=>$name", this, wireLogs, pubsubLogs, false)
        listOf(thisChannel, anotherChannel).forEach {
            it.attr(PROTOCOL).get().complete(this.protocol)
        }
        return TestChannel.interConnect(thisChannel, anotherChannel)
    }

    fun connectSemiDuplex(
        another: TestRouter,
        wireLogs: LogLevel? = null,
        pubsubLogs: LogLevel? = null
    ): SemiduplexConnection {
        return SemiduplexConnection(
            connect(another, wireLogs, pubsubLogs),
            another.connect(this, wireLogs, pubsubLogs)
        )
    }
}