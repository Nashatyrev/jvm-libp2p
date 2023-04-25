package io.libp2p.simulate.main.tcp

import io.libp2p.etc.types.toByteBuf
import io.netty.channel.ChannelHandler
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


fun main() {
    val test = TcpMultiTest()
    test.setup()
    test.runInbound()
}

class TcpMultiTest(
    val destHost: String = "localhost", // "45.79.117.81",
    val serverPort: Int = 7777,
    val clientPortStart: Int = 8000,
    val msgSize: Int = 512 * 1024,
    val clientCount: Int = 8,
    val staggeringDelay: Duration = Duration.ZERO,
    val delayAfterMessage: Duration = 5.seconds,
    val messagesCount: Int = 10,
    val handlers: List<ChannelHandler> = emptyList()
) {

    lateinit var server: ServerTcpNode
    val clients = mutableListOf<ClientTcpNode>()


    fun setup() {
        startServer()
        createClients()
        connect()
    }

    fun shutdown() {
        (clients + server).forEach { it.close() }

        Thread.sleep(1000)
    }

    fun startServer() {
        server = DefaultTcpServerNode(serverPort, destHost, logEachConnection = false, handlers = handlers)
    }

    fun createClients() {
        clients +=
            (0 until clientCount)
                .map {
                    DefaultTcpClientNode(it, /*clientPortStart + it,*/ handlers = handlers)
                }
    }

    fun connect() {
        clients.forEach {
            it.connect(server)
        }
        Thread.sleep(1000)
    }

    fun runInbound() {
        repeat (messagesCount) {
            resetTcpNodeLoggers()

            clients
                .map {
                    val data = ByteArray(msgSize)
                    val ret = it.connections[0].writeAndFlush(data.toByteBuf())
                    Thread.sleep(staggeringDelay.inWholeMilliseconds)
                    ret
                }
                .forEach { it.sync() }

            Thread.sleep(delayAfterMessage.inWholeMilliseconds)
        }
    }

    fun runOutbound() {
        repeat (messagesCount) {
            resetTcpNodeLoggers()

            server.connections
                .map {
                    val data = ByteArray(msgSize)
                    val ret  = it.writeAndFlush(data.toByteBuf())
                    Thread.sleep(staggeringDelay.inWholeMilliseconds)
                    ret
                }
                .forEach { it.sync() }

            Thread.sleep(delayAfterMessage.inWholeMilliseconds)
        }
    }
}

