package io.libp2p.quicsim.sim.impl.netty

import io.libp2p.quicsim.core.schedule.impl.SimpleNettyTicker
import io.netty.buffer.ByteBufAllocator
import io.netty.channel.ChannelHandler
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.concurrent.Ticker
import java.net.InetSocketAddress

class SimDatagramChannel private constructor (
    builder: Builder,
    private val local: InetSocketAddress,
) : EmbeddedChannel(builder) {

    lateinit var ticker: SimpleNettyTicker

    constructor(
        id: String,
        local: InetSocketAddress,
        handler: ChannelHandler,
        allocator: ByteBufAllocator? = null,
        ticker: SimpleNettyTicker = SimpleNettyTicker()
    ) : this(
        builder()
            .channelId(SimChannelId(id))
            .register(false)
            .handlers(handler)
            .ticker(ticker),
        local
    ) {
        this.ticker = ticker
        allocator?.let { config().setAllocator(it) }
        register()
    }

    override fun localAddress(): InetSocketAddress = local
    override fun remoteAddress(): InetSocketAddress? = null
}
