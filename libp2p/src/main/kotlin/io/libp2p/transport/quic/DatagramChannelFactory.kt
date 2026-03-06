package io.libp2p.transport.quic

import io.netty.channel.ChannelHandler
import io.netty.channel.Channel
import java.net.SocketAddress
import java.util.concurrent.CompletableFuture

interface DatagramChannelFactory {
    fun createClientChannel(handler: ChannelHandler): CompletableFuture<Channel>
    fun createServerChannel(bindAddress: SocketAddress, handler: ChannelHandler): CompletableFuture<Channel>
    fun shutdown(): CompletableFuture<Unit>
}
