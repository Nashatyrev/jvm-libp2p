package io.libp2p.quicsim.host.impl.sim

import io.netty.channel.ChannelHandler
import io.netty.channel.embedded.EmbeddedChannel
import java.net.InetSocketAddress

class SimDatagramChannel(
    id: String,
    private val local: InetSocketAddress,
    handler: ChannelHandler
) : EmbeddedChannel(SimChannelId(id), handler) {
    override fun localAddress(): InetSocketAddress = local
    override fun remoteAddress(): InetSocketAddress? = null
}