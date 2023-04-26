package io.libp2p.simulate.main.tcp

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@Sharable
class ReadSizeCounter : ChannelInboundHandlerAdapter() {

    val readSize = AtomicLong()

    fun reset() = readSize.set(0)

    fun waitFor(targetSize: Long, timeout: Duration = 5.minutes) {
        val tt = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (readSize.get() < targetSize) {
            Thread.sleep(500)

            if (System.currentTimeMillis() > tt) {
                throw TimeoutException()
            }
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as ByteBuf
        readSize.addAndGet(msg.readableBytes().toLong())
        super.channelRead(ctx, msg)
    }
}