package io.libp2p.simulate.main.tcp

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import java.lang.System.currentTimeMillis
import java.net.InetSocketAddress
import java.util.Vector

@Sharable
class EventRecordingHandler : ChannelDuplexHandler() {

    enum class EventType { WRITE, WRITTEN, READ }

    @kotlinx.serialization.Serializable
    data class Event(
        val time: Long,
        val localPort: Int,
        val remotePort: Int,
        val type: EventType,
        val size: Long
    ) {
        companion object {
            fun create(ch: Channel, type: EventType, size: Int): Event =
                Event(
                    currentTimeMillis(),
                    (ch.localAddress() as InetSocketAddress).port,
                    (ch.remoteAddress() as InetSocketAddress).port,
                    type,
                    size.toLong()
                )
        }
    }

    val events = Vector<Event>()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as ByteBuf
        events += Event.create(ctx.channel(), EventType.READ, msg.readableBytes())

        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val size = (msg as ByteBuf).readableBytes()
        events += Event.create(ctx.channel(), EventType.WRITE, size)

        promise.addListener {
            events += Event.create(ctx.channel(), EventType.WRITTEN, size)
        }

        super.write(ctx, msg, promise)
    }
}