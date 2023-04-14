package io.libp2p.simulate.main.tcp

import io.libp2p.etc.util.netty.LoggingHandlerShort
import io.libp2p.tools.log
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.logging.LogLevel


fun main() {
    TcpTest().run()
}

class TcpTest {

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

    fun startClient(port: Int) {
        val host = "localhost"
        val workerGroup: EventLoopGroup = NioEventLoopGroup()

        try {
            val b = Bootstrap()
            b.group(workerGroup)
            b.channel(NioSocketChannel::class.java)
            b.option(ChannelOption.SO_KEEPALIVE, true)
            b.handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline().addLast(LoggingHandlerShort("client", LogLevel.ERROR))
                }
            })
            val f: ChannelFuture = b.connect(host, port).sync()
            f.channel().closeFuture().sync()
        } finally {
            workerGroup.shutdownGracefully()
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
                        ch.pipeline().addLast(LoggingHandlerShort("server", LogLevel.ERROR))
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