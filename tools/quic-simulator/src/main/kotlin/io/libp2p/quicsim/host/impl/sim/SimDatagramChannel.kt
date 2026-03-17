package io.libp2p.quicsim.host.impl.sim

import io.netty.channel.ChannelHandler
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.Ticker
import java.net.InetSocketAddress

class SimDatagramChannel(
    builder: Builder,
    private val local: InetSocketAddress,
) : EmbeddedChannel(builder) {

    constructor(
        id: String,
        local: InetSocketAddress,
        handler: ChannelHandler,
        ticker: Ticker
    ) : this(
        builder()
            .channelId(SimChannelId(id))
            .handlers(handler)
            .ticker(ticker),
        local
    )

    override fun localAddress(): InetSocketAddress = local
    override fun remoteAddress(): InetSocketAddress? = null
}