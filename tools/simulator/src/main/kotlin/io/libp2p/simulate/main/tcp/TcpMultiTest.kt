package io.libp2p.simulate.main.tcp

import io.libp2p.etc.types.toByteBuf
import io.libp2p.tools.log
import io.netty.channel.ChannelHandler
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


fun main() {
    val test = TcpMultiTest(
        clientCount = 2,
        delayAfterMessage = 10.seconds,
        messagesCount = 1000
    )
    test.setup()
    test.runInbound()
}

class TcpMultiTest(
    val destHost: String = "localhost", // "45.79.117.81",
    val serverPort: Int = 7777,
    val msgSize: Int = 512 * 1024,
    val clientCount: Int = 8,
    val clientAddresses: List<InetSocketAddress> = loopbackAddresses(8000, clientCount),
    val staggeringDelay: Duration = Duration.ZERO,
    val delayAfterMessage: Duration = 2.seconds,
    val messagesCount: Int = 10,
    val loggersEnabled: Boolean = true,
    val handlers: List<ChannelHandler> = emptyList()
) {

    lateinit var server: ServerTcpNode
    val clients = mutableListOf<ClientTcpNode>()
    private val readSizeHandler = ReadSizeCounter()

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
        server = DefaultTcpServerNode(
            serverPort,
            destHost,
            logEachConnection = false,
            loggersEnabled = loggersEnabled,
            handlers = handlers + readSizeHandler
        )
    }

    fun createClients() {
        clients +=
            (0 until clientCount)
                .map { index ->
                    DefaultTcpClientNode(
                        index,
                        sourceAddress = clientAddresses[index],
                        loggersEnabled = loggersEnabled, /*clientPortStart + index,*/
                        handlers = handlers + readSizeHandler
                    )
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
            readSizeHandler.reset()

            clients
                .map {
                    val data = ByteArray(msgSize)
                    val ret = it.connections[0].writeAndFlush(data.toByteBuf())
                    Thread.sleep(staggeringDelay.inWholeMilliseconds)
                    ret
                }
                .forEach { it.sync() }

            readSizeHandler.waitFor(msgSize.toLong() * clients.size)
            Thread.sleep(delayAfterMessage.inWholeMilliseconds)
        }
    }

    fun runOutboundSingle(connectionNum: Int) {
        readSizeHandler.reset()
        val conn = server.connections[connectionNum]
        conn
            .writeAndFlush(ByteArray(msgSize).toByteBuf())
            .sync()
        readSizeHandler.waitFor(msgSize.toLong())
    }

    fun runOutbound() {
        repeat (messagesCount) {
            resetTcpNodeLoggers()
            readSizeHandler.reset()

            server.connections
                .map {
                    val data = ByteArray(msgSize)
                    val ret  = it.writeAndFlush(data.toByteBuf())
                    Thread.sleep(staggeringDelay.inWholeMilliseconds)
                    ret
                }
                .forEach { it.sync() }

            readSizeHandler.waitFor(msgSize.toLong() * clients.size)
            Thread.sleep(delayAfterMessage.inWholeMilliseconds)
        }
    }

    companion object {
        fun loopbackAddresses(startPort: Int, count: Int) =
            loopbackAddressGenerator(startPort).take(count).toList()
        fun loopbackAddressGenerator(startPort: Int) = sequence {
            var ipLastByte = 2
            var port = startPort
            while (true) {
                yield(InetSocketAddress("127.0.0.$ipLastByte", port))
                ipLastByte++
                port++
                if (ipLastByte > 127) throw IllegalArgumentException("Can't generate more than 125 loopback ips")
            }
        }
    }
}

