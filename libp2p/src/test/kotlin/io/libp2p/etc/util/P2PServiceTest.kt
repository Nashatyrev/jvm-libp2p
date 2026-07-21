package io.libp2p.etc.util

import io.libp2p.tools.StreamStub
import io.netty.channel.Channel
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class P2PServiceTest {

    @Test
    fun `enqueueWrite invokes supplier and sequence on service event thread`() {
        val serviceThreadName = "p2p-service-test"
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, serviceThreadName)
        }

        try {
            val service = TestP2PService(executor)
            service.addNewStream(StreamStub())
            service.awaitEventThread()

            val calls = mutableListOf<String>()
            val result = service.streamHandler.enqueueWrite {
                calls += "supplier:${Thread.currentThread().name}"
                sequence {
                    calls += "first:${Thread.currentThread().name}"
                    yield("one")
                    calls += "second:${Thread.currentThread().name}"
                    yield("two")
                }
            }
            service.awaitEventThread()
            result.get(5, TimeUnit.SECONDS)

            assertThat(calls).containsExactly(
                "supplier:$serviceThreadName",
                "first:$serviceThreadName",
                "second:$serviceThreadName"
            )
            assertThat(service.channel.readOutbound<String>()).isEqualTo("one")
            assertThat(service.channel.readOutbound<String>()).isEqualTo("two")
            assertThat(service.channel.readOutbound<String>()).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `enqueueWrite sequence overload iterates sequence on service event thread`() {
        val serviceThreadName = "p2p-service-test"
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, serviceThreadName)
        }

        try {
            val service = TestP2PService(executor)
            service.addNewStream(StreamStub())
            service.awaitEventThread()

            val calls = mutableListOf<String>()
            val result = service.streamHandler.enqueueWrite(
                sequence {
                    calls += Thread.currentThread().name
                    yield("message")
                }
            )
            service.awaitEventThread()
            result.get(5, TimeUnit.SECONDS)

            assertThat(calls).containsExactly(serviceThreadName)
            assertThat(service.channel.readOutbound<String>()).isEqualTo("message")
            assertThat(service.channel.readOutbound<String>()).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `enqueueWrite pulls sequence with next without probing hasNext`() {
        val serviceThreadName = "p2p-service-test"
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, serviceThreadName)
        }

        try {
            val service = TestP2PService(executor)
            service.addNewStream(StreamStub())
            service.awaitEventThread()

            val calls = mutableListOf<String>()
            val result = service.streamHandler.enqueueWrite(
                object : Sequence<Any> {
                    override fun iterator(): Iterator<Any> =
                        object : Iterator<Any> {
                            private var emitted = false

                            override fun hasNext(): Boolean {
                                error("hasNext should not be used to pull queued writes")
                            }

                            override fun next(): Any {
                                calls += "next:${Thread.currentThread().name}"
                                if (!emitted) {
                                    emitted = true
                                    return "message"
                                }
                                throw NoSuchElementException()
                            }
                        }
                }
            )
            service.awaitEventThread()
            result.get(5, TimeUnit.SECONDS)

            assertThat(calls).containsExactly("next:$serviceThreadName", "next:$serviceThreadName")
            assertThat(service.channel.readOutbound<String>()).isEqualTo("message")
            assertThat(service.channel.readOutbound<String>()).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `enqueueWrite resumes sequence when channel becomes writable`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val backpressure = OneWriteBackpressureHandler()

        try {
            val service = TestP2PService(executor, backpressure)
            service.addNewStream(StreamStub())
            service.awaitEventThread()

            val generated = mutableListOf<Int>()
            val result = service.streamHandler.enqueueWrite(
                sequence {
                    for (i in 1..3) {
                        generated += i
                        yield("message-$i")
                    }
                }
            )
            service.awaitEventThread()

            assertThat(generated).containsExactly(1)
            assertThat(result).isNotDone()
            assertThat(service.channel.readOutbound<String>()).isEqualTo("message-1")
            assertThat(service.channel.readOutbound<String>()).isNull()

            backpressure.release(service.channel)
            service.awaitEventThread()
            result.get(5, TimeUnit.SECONDS)

            assertThat(generated).containsExactly(1, 2, 3)
            assertThat(service.channel.readOutbound<String>()).isEqualTo("message-2")
            assertThat(service.channel.readOutbound<String>()).isEqualTo("message-3")
            assertThat(service.channel.readOutbound<String>()).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `enqueueWrite completes after all underlying write futures complete`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val delayedWrites = DelayedWriteHandler()

        try {
            val service = TestP2PService(executor, delayedWrites)
            service.addNewStream(StreamStub())
            service.awaitEventThread()

            val result = service.streamHandler.enqueueWrite(sequenceOf("one", "two"))
            service.awaitEventThread()

            assertThat(result).isNotDone()
            assertThat(delayedWrites.pendingWrites).hasSize(2)
            assertThat(service.channel.readOutbound<String>()).isNull()

            delayedWrites.releaseOne()
            service.awaitEventThread()

            assertThat(result).isNotDone()
            assertThat(service.channel.readOutbound<String>()).isEqualTo("one")
            assertThat(service.channel.readOutbound<String>()).isNull()

            delayedWrites.releaseOne()
            service.awaitEventThread()
            result.get(5, TimeUnit.SECONDS)

            assertThat(service.channel.readOutbound<String>()).isEqualTo("two")
            assertThat(service.channel.readOutbound<String>()).isNull()
        } finally {
            executor.shutdownNow()
        }
    }

    private class TestP2PService(
        executor: ScheduledExecutorService,
        private vararg val handlers: ChannelHandler
    ) : P2PService(executor) {
        lateinit var streamHandler: StreamHandler
        lateinit var channel: EmbeddedChannel

        override fun initChannel(streamHandler: StreamHandler) {
            this.streamHandler = streamHandler
            channel = EmbeddedChannel(*handlers, streamHandler)
        }

        override fun onPeerActive(peer: PeerHandler) {}

        override fun onPeerDisconnected(peer: PeerHandler) {}

        override fun onInbound(peer: PeerHandler, msg: Any) {}

        fun awaitEventThread() {
            submitOnEventThread { }.get(5, TimeUnit.SECONDS)
        }
    }

    private class OneWriteBackpressureHandler : ChannelOutboundHandlerAdapter() {
        private var writes = 0

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            writes++
            ctx.write(msg, promise)
            if (writes == 1) {
                setWritable(ctx.channel(), false)
                ctx.fireChannelWritabilityChanged()
            }
        }

        fun release(channel: Channel) {
            setWritable(channel, true)
            channel.pipeline().fireChannelWritabilityChanged()
        }

        private fun setWritable(channel: Channel, writable: Boolean) {
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, writable)
        }
    }

    private class DelayedWriteHandler : ChannelOutboundHandlerAdapter() {
        private lateinit var ctx: ChannelHandlerContext
        val pendingWrites = ArrayDeque<Pair<Any, ChannelPromise>>()

        override fun handlerAdded(ctx: ChannelHandlerContext) {
            this.ctx = ctx
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            pendingWrites.add(msg to promise)
        }

        fun releaseOne() {
            val (msg, promise) = pendingWrites.removeFirst()
            ctx.write(msg, promise)
            ctx.flush()
        }
    }
}
