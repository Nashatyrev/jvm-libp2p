package io.libp2p.simulate.main.tcp

import io.libp2p.tools.log
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds

interface TcpNode {

    val connections: List<Channel>

    fun close() {
        connections
            .map { it.close() }
            .forEach { it.sync() }
    }
}

interface ClientTcpNode : TcpNode {

    fun connect(server: ServerTcpNode): Channel
}

interface ServerTcpNode : TcpNode {

    val listenAddress: InetSocketAddress
}

private val loggers = mutableListOf<SizeChannelLogger>()

private fun createLogger(name: String, logger: (String) -> Unit = { log(it) }) =
    SizeChannelLogger(name, {
        (it as ByteBuf).readableBytes().toLong()
    }, 100.milliseconds, 1, false, logger)
        .also { loggers += it }


fun resetTcpNodeLoggers() = loggers.forEach { it.reset() }

val commonLogHandler = createLogger("client")

class DefaultTcpClientNode(
    val number: Int,
    val sourcePort: Int? = null,
    val handlers: List<ChannelHandler> = emptyList(),
    val loggersEnabled: Boolean = true
) : ClientTcpNode {

    override val connections = mutableListOf<Channel>()

    private val workerGroup: EventLoopGroup = NioEventLoopGroup()

    override fun connect(server: ServerTcpNode): Channel {
        val b = Bootstrap()
        b.group(workerGroup)
        b.channel(NioSocketChannel::class.java)
        b.option(ChannelOption.SO_KEEPALIVE, true)
        b.option(ChannelOption.SO_REUSEADDR, true)
        b.handler(object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
//                    ch.pipeline().addLast(createLoggingHandler("client"))
                if (loggersEnabled) {
                    ch.pipeline().addLast(commonLogHandler)
                    ch.pipeline().addLast(createLogger("client-$number"))
                }

                handlers.forEach {
                    ch.pipeline().addLast(it)
                }
            }
        })
        val f: ChannelFuture =
            if (sourcePort != null) {
                b.connect(server.listenAddress, InetSocketAddress(sourcePort))
            } else {
                b.connect(server.listenAddress)
            }
        val ch = f.sync().channel()
        connections += ch
        return ch
    }
}

class DefaultTcpServerNode(
    val listenPort: Int,
    val listenHost: String = "127.0.0.1",
    val logEachConnection: Boolean = true,
    val handlers: List<ChannelHandler> = emptyList(),
    val loggersEnabled: Boolean = true
) : ServerTcpNode {

    override val connections = mutableListOf<Channel>()
    override val listenAddress = InetSocketAddress(listenHost, listenPort)

    val serverChannel: Channel
    private val workerGroup: EventLoopGroup = NioEventLoopGroup()

    override fun close() {
        super.close()
        serverChannel.close().sync()
    }

    init {
        var childChannelCount = 0
        val commonLogger: (String) -> Unit = {
            if (!logEachConnection || childChannelCount > 1) {
                log(it)
            }
        }
        val commonLogHandler =
            if (loggersEnabled) {
                createLogger("server", commonLogger)
            } else {
                ChannelInboundHandlerAdapter()
            }

        val b = ServerBootstrap()
        b.group(workerGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_REUSEADDR, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    if (loggersEnabled) {
                        ch.pipeline().addLast(commonLogHandler)
                        if (logEachConnection) {
                            ch.pipeline().addLast(createLogger("server-$childChannelCount"))
                        }
                    }

                    childChannelCount++
                    connections += ch

                    handlers.forEach {
                        ch.pipeline().addLast(it)
                    }
//                        ch.pipeline().addLast(EchoHandler())
                }
            })
            .option(ChannelOption.SO_BACKLOG, 128)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
        serverChannel = b.bind(listenPort).sync().channel()
    }
}
