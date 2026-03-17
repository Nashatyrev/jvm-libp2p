package io.libp2p.quicsim.host.impl.sim

import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import io.libp2p.quicsim.core.schedule.impl.NettyTicker
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class EmbeddedChannelDatagramPacketProcessorTest {

    @Test
    @Timeout(5)
    fun `scheduled task is executed only after time advance with netty ticker`() {
        val scheduler = DeterministicScheduler()
        val ticker = NettyTicker(scheduler)

        val addrA = InetSocketAddress("10.0.0.1", 17000)
        val addrB = InetSocketAddress("10.0.0.2", 17001)
        val receivedAtB = CopyOnWriteArrayList<String>()

        val channelA = SimDatagramChannel(
            "a",
            addrA,
            object : SimpleChannelInboundHandler<DatagramPacket>() {
                override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
                    val copiedPayload = msg.content().copy()
                    ctx.executor().schedule({
                        ctx.writeAndFlush(DatagramPacket(copiedPayload, addrB, addrA))
                    }, 1, TimeUnit.MILLISECONDS)
                }
            },
            ticker
        )

        val channelB = SimDatagramChannel(
            "b",
            addrB,
            object : SimpleChannelInboundHandler<DatagramPacket>() {
                override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
                    val bytes = ByteArray(msg.content().readableBytes())
                    msg.content().getBytes(msg.content().readerIndex(), bytes)
                    receivedAtB += String(bytes, StandardCharsets.UTF_8)
                }
            },
            ticker
        )

        val procA = EmbeddedChannelDatagramPacketProcessor(channelA)
        val procB = EmbeddedChannelDatagramPacketProcessor(channelB)

        val outboundA0 = procA.deliver(
            listOf(DatagramPacket(Unpooled.wrappedBuffer("ping".toByteArray()), addrA, addrB))
        )
        assertTrue(outboundA0.isEmpty(), "No packet should be emitted before scheduled delay elapses")
        assertTrue(receivedAtB.isEmpty(), "Receiver should still be empty before time advance")

        scheduler.advanceAndExecuteAll(1.milliseconds)
        procA.advanceAndExecuteAll(1.milliseconds)

        val outboundA1 = procA.deliver(emptyList())
        procB.deliver(outboundA1)

        assertEquals(listOf("ping"), receivedAtB)
    }
}

