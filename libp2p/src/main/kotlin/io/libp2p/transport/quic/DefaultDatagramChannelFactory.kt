package io.libp2p.transport.quic

import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toCompletableFuture
import io.libp2p.etc.types.toVoidCompletableFuture
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelOption
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollDatagramChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioDatagramChannel
import java.net.SocketAddress
import java.time.Duration
import java.util.concurrent.CompletableFuture

class DefaultDatagramChannelFactory(
    val connectTimeout: Duration = Duration.ofSeconds(15)
) : DatagramChannelFactory {

    private var workerGroup by lazyVar {
        MultiThreadIoEventLoopGroup(NioIoHandler.newFactory())
    }

    private val channelClass =
        if (Epoll.isAvailable()) {
            EpollDatagramChannel::class.java
        } else {
            NioDatagramChannel::class.java
        }

    private val clientBootstrap by lazyVar {
        Bootstrap().group(workerGroup)
            .channel(channelClass)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
    }

    private val serverBootstrap by lazyVar {
        Bootstrap().group(workerGroup)
            .channel(channelClass)
    }

    override fun createClientChannel(handler: ChannelHandler): CompletableFuture<Channel> =
        clientBootstrap.clone()
            .handler(handler)
            .bind(0)
            .toCompletableFuture()


    override fun createServerChannel(
        bindAddress: SocketAddress,
        handler: ChannelHandler
    ): CompletableFuture<Channel> =
        serverBootstrap.clone()
            .handler(handler)
            .bind(bindAddress)
            .toCompletableFuture()

    override fun shutdown(): CompletableFuture<Unit> =
        workerGroup.shutdownGracefully().toVoidCompletableFuture()


}
