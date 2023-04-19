package io.libp2p.simulate.main.tcp

import io.libp2p.etc.types.toByteBuf
import io.libp2p.etc.util.netty.LoggingHandlerShort
import io.libp2p.simulate.util.ReadableSize
import io.libp2p.tools.log
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds


fun main() {
    TcpTest().run()
}

class TcpTest {

    fun createLoggingHandler(name: String) =
        LoggingHandlerShort(name, LogLevel.ERROR).apply {
            maxHeadingLines = 1
            maxTrailingLines = 0
        }

    fun run() {
        val port = 7777

        Thread {
            log("Starting server...")
            startServer(port)
            log("Server completed")
        }.start()

        Thread.sleep(1000)

        Thread {
            log("Starting client...")
            startClient(port)
            log("Client completed")
        }.start()
    }

    val loggers = mutableListOf<SizeChannelLogger>()

    fun createLogger(name: String) =
        SizeChannelLogger(name, {
            (it as ByteBuf).readableBytes().toLong()
        }, 100.milliseconds, 5)
            .also { loggers += it }

    fun startClient(port: Int) {
        val host = "45.79.117.81"
//        val host = "localhost"
        val workerGroup: EventLoopGroup = NioEventLoopGroup()

        try {
            val b = Bootstrap()
            b.group(workerGroup)
            b.channel(NioSocketChannel::class.java)
            b.option(ChannelOption.SO_KEEPALIVE, true)
            b.handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
//                    ch.pipeline().addLast(createLoggingHandler("client"))
                    ch.pipeline().addLast(createLogger("client"))
                }
            })
            val f: ChannelFuture = b.connect(InetSocketAddress(host, port), InetSocketAddress(8888)).sync()

            Thread.sleep(1000)

            while (true) {
                loggers.forEach { it.reset() }

                val data = ByteArray(1 * 1024 * 1024)
                f.channel().writeAndFlush(data.toByteBuf()).sync()

                Thread.sleep(10000)
            }
//            log("Closing connection")
//            f.channel().close().sync()
//            log("Done")
        } finally {
            workerGroup.shutdownGracefully()
        }

    }

    class EchoHandler : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            msg as ByteBuf
            ctx.writeAndFlush(msg.retain())
        }
    }

    fun startServer(port: Int) {
        val group: EventLoopGroup = NioEventLoopGroup()
        try {
            val b = ServerBootstrap()
            b.group(group, group)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
//                        ch.pipeline().addLast(createLoggingHandler("server"))
                        ch.pipeline().addLast(createLogger("server"))
//                        ch.pipeline().addLast(EchoHandler())
                    }
                }).option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
            val f: ChannelFuture = b.bind(port).sync()
            f.channel().closeFuture().sync()
        } finally {
            group.shutdownGracefully()
        }
    }
}

