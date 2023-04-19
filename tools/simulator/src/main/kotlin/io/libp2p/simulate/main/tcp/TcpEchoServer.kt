package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.util.ReadableSize
import io.libp2p.tools.log
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel

class TcpEchoServer(
    val port: Int = 7777
) {

    fun run() {
        log("Starting server...")
        startServer(port)
        log("Server completed")
    }

    class ReadWriteChannelLogger(val name: String) : ChannelDuplexHandler() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            msg as ByteBuf

            val size = ReadableSize.create(msg.readableBytes())
            log("[$name] Read $size")

            super.channelRead(ctx, msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            msg as ByteBuf
            val size = ReadableSize.create(msg.readableBytes())
            log("[$name] Write $size")
            promise.addListener {
                log("[$name] Written $size")
            }
            super.write(ctx, msg, promise)
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
                        ch.pipeline().addLast(ReadWriteChannelLogger("server"))
                        ch.pipeline().addLast(EchoHandler())
                    }
                }).option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
            val f: ChannelFuture = b.bind(port).sync()
            log("Server started")
            f.channel().closeFuture().sync()
        } finally {
            group.shutdownGracefully()
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            TcpEchoServer().run()
        }
    }
}

