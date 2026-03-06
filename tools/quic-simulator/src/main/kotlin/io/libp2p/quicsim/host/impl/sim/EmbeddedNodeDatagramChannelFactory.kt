package io.libp2p.quicsim.host.impl.sim

import io.libp2p.transport.quic.DatagramChannelFactory
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import java.net.SocketAddress
import java.util.concurrent.CompletableFuture

class EmbeddedNodeDatagramChannelFactory(
    private val node: SimulatedRunner.EmbeddedNode
) : DatagramChannelFactory {
    override fun createClientChannel(handler: ChannelHandler): CompletableFuture<Channel> =
        node.bindClientParent(handler)

    override fun createServerChannel(
        bindAddress: SocketAddress,
        handler: ChannelHandler
    ): CompletableFuture<Channel> = node.bindServerParent(bindAddress, handler)

    override fun shutdown(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}