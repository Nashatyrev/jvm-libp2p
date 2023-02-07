package io.libp2p.simulate.stream

import io.libp2p.etc.types.lazyVar
import io.libp2p.etc.types.toVoidCompletableFuture
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.util.GeneralSizeEstimator
import io.libp2p.simulate.MessageDelayer
import io.libp2p.simulate.sequential
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelId
import io.netty.channel.ChannelPromise
import io.netty.channel.DefaultChannelPromise
import io.netty.channel.EventLoop
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.internal.ObjectUtil
import org.apache.logging.log4j.LogManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class StreamSimChannel(
    id: String,
    val inboundBandwidth: BandwidthDelayer,
    val outboundBandwidth: BandwidthDelayer,
    vararg handlers: ChannelHandler?
) :
    EmbeddedChannel(
        SimChannelId(id),
        *handlers
    ) {

    var link: StreamSimChannel? = null
    var executor: ScheduledExecutorService by lazyVar { Executors.newSingleThreadScheduledExecutor() }
    var currentTime: () -> Long = System::currentTimeMillis
    var msgSizeEstimator = GeneralSizeEstimator
    private var msgDelayer: MessageDelayer by lazyVar {
        BandwidthDelayer
            .createMessageDelayer(outboundBandwidth, MessageDelayer.NO_DELAYER, inboundBandwidth)
            .sequential(executor)
    }

    var msgSizeHandler: (Int) -> Unit = {}

    fun setLatency(latency: MessageDelayer) {
        msgDelayer = BandwidthDelayer
            .createMessageDelayer(outboundBandwidth, latency, inboundBandwidth)
            .sequential(executor)
    }

    @Synchronized
    fun connect(other: StreamSimChannel) {
        while (outboundMessages().isNotEmpty()) {
            send(other, outboundMessages().poll())
        }
        link = other
    }

    @Synchronized
    override fun handleOutboundMessage(msg: Any) {
        if (link != null) {
            send(link!!, msg)
        } else {
            super.handleOutboundMessage(msg)
        }
    }

    private fun send(other: StreamSimChannel, msg: Any) {
        val size = msgSizeEstimator(msg)
        val delay = msgDelayer.delay(size)

        val sendNow: () -> Unit = {
            other.writeInbound(msg)
            msgSizeHandler(size.toInt())
        }

        delay.thenApply {
            other.executor.execute(sendNow)
        }

//        // this prevents message reordering
//        val curT = currentTime()
//        val timeToSend = curT + delay
//        if (timeToSend < lastTimeToSend) {
//            delay = lastTimeToSend - curT
//        }
//        lastTimeToSend = curT + delay
//        if (delay > 0) {
//            other.executor.schedule(sendNow, delay, TimeUnit.MILLISECONDS)
//        } else {
//            other.executor.execute(sendNow)
//        }
    }

    private open class DelegatingEventLoop(val delegate: EventLoop) : EventLoop by delegate

    override fun eventLoop(): EventLoop {
        return object : DelegatingEventLoop(super.eventLoop()) {
            override fun execute(command: Runnable) {
                super.execute(command)
                runPendingTasks()
            }

            override fun register(channel: Channel): ChannelFuture {
                return register(DefaultChannelPromise(channel, this))
            }

            override fun register(promise: ChannelPromise): ChannelFuture {
                ObjectUtil.checkNotNull(promise, "promise")
                promise.channel().unsafe().register(this, promise)
                return promise
            }

            override fun register(channel: Channel, promise: ChannelPromise): ChannelFuture {
                channel.unsafe().register(this, promise)
                return promise
            }
        }
    }

    companion object {
        fun interConnect(ch1: StreamSimChannel, ch2: StreamSimChannel): Connection {
            ch1.connect(ch2)
            ch2.connect(ch1)
            return Connection(ch1, ch2)
        }

        private val logger = LogManager.getLogger(StreamSimChannel::class.java)
    }

    class Connection(val ch1: StreamSimChannel, val ch2: StreamSimChannel) {

        fun setLatency(latency: MessageDelayer) {
            ch1.setLatency(latency)
            ch2.setLatency(latency)
        }
        fun disconnect(): CompletableFuture<Unit> {
            return CompletableFuture.allOf(
                ch1.close().toVoidCompletableFuture(),
                ch2.close().toVoidCompletableFuture()
            ).thenApply { Unit }
        }
    }
}

private class SimChannelId(val id: String) : ChannelId {
    override fun compareTo(other: ChannelId) = asLongText().compareTo(other.asLongText())
    override fun asShortText() = id
    override fun asLongText() = id
}
