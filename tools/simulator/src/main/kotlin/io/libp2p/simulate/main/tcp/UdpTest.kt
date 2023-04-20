package io.libp2p.simulate.main.tcp

import io.libp2p.etc.types.toByteBuf
import io.libp2p.tools.log
import io.netty.bootstrap.Bootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.DatagramChannel
import io.netty.channel.socket.DatagramPacket
import io.netty.channel.socket.nio.NioDatagramChannel
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    UdpTest().run()
}

class UdpTest(
    val destHost: String = "localhost",
    val destPort: Int = 7777,
    val srcPort: Int = 8888,
    val msgSize: Int = 512,
    val msgCount: Int = 2 * 1024
) {

    fun run() {

        Thread {
            log("Starting server...")
            startServer(destPort)
            log("Server completed")
        }.start()

        Thread.sleep(1000)

        Thread {
            log("Starting client...")
            startClient(destHost, destPort, srcPort)
            log("Client completed")
        }.start()
    }

    fun startClient(destHost: String, destPort: Int, srcPort: Int) {
//        val host = "45.79.117.81"
        val workerGroup: EventLoopGroup = NioEventLoopGroup()

        try {
            val b = Bootstrap()
            b.group(workerGroup)
            b.channel(NioDatagramChannel::class.java)
            b.handler(object : ChannelInitializer<DatagramChannel>() {
                override fun initChannel(ch: DatagramChannel) {
//                    ch.pipeline().addLast(createLoggingHandler("client"))
                    ch.pipeline().addLast(createLogger("client"))
                }
            })

            val f: ChannelFuture = b.bind(srcPort).sync()

            Thread.sleep(1000)

            val destAddress = InetSocketAddress(destHost, destPort)
            while (true) {
                loggers.forEach { it.reset() }

                repeat(msgCount) {
                    val data = ByteArray(msgSize).toByteBuf()
                    val datagramPacket = DatagramPacket(data, destAddress)
                    f.channel().writeAndFlush(datagramPacket)
                }

                Thread.sleep(10000)
            }
//            log("Closing connection")
//            f.channel().close().sync()
//            log("Done")
        } finally {
            workerGroup.shutdownGracefully()
        }

    }
    fun startServer(port: Int) {
        val group: EventLoopGroup = NioEventLoopGroup()
        try {
            val b = Bootstrap()
            b.group(group)
                .channel(NioDatagramChannel::class.java)
                .handler(object : ChannelInitializer<DatagramChannel>() {
                    override fun initChannel(ch: DatagramChannel) {
                        ch.pipeline().addLast(createLogger("server"))
//                        ch.pipeline().addLast(EchoHandler())
                    }
                })
            val f: ChannelFuture = b.bind(port).sync()
            f.channel().closeFuture().sync()
        } finally {
            group.shutdownGracefully()
        }
    }

    val loggers = mutableListOf<SizeChannelLogger>()

    private fun createLogger(name: String): ChannelHandler =
        SizeChannelLogger(name, {
            (it as DatagramPacket).content().readableBytes().toLong()
        }, 50.milliseconds, 1)
            .also { loggers += it }
}
