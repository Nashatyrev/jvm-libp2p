package io.libp2p.pubsub.erasure

import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.DeterministicFuzzRouterFactory
import io.libp2p.pubsub.PubsubRouterDebug
import io.libp2p.pubsub.PubsubRouterTest
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy.Companion.ALWAYS_RESPOND_TO_INBOUND_SAMPLES
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

val random = Random(1)
val ackSendStrategy: () -> AckSendStrategy = { ALWAYS_RESPOND_TO_INBOUND_SAMPLES }
val sampleSendStrategy: () -> SampleSendStrategy = { SampleSendStrategy.sendAll() }

fun createErasureFuzzRouterFactory(): DeterministicFuzzRouterFactory =
    object : DeterministicFuzzRouterFactory {
        private var counter = 0
        override fun invoke(
            executor: ScheduledExecutorService,
            p2: CurrentTimeSupplier,
            random: java.util.Random
        ): PubsubRouterDebug {
            val erasureCoder: ErasureCoder = TestErasureCoder(4, 4)
            val messageRouterFactory: MessageRouterFactory = TestMessageRouterFactory(random, ackSendStrategy, sampleSendStrategy)

            val erasureRouter = ErasureRouter(executor, erasureCoder, messageRouterFactory)
            erasureRouter.name = "$counter"
            counter++
            return erasureRouter
        }

    }


class ErasurePubsubRouterTest(
) : PubsubRouterTest(createErasureFuzzRouterFactory()) {

    val topic = "topic1"
    val fuzz = DeterministicFuzz()

    private fun createRouterAndSubscribe() =
        fuzz.createTestRouter(routerFactory).also {
            it.router.subscribe(topic)
        }

    @Test
    fun `simple case`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isNotNull
        assertThat(router1.inboundMessages).isEmpty()
        assertThat(router2.inboundMessages).isEmpty()
    }

    @Test
    fun `2 receive routers in series`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()
        val router3 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR)
        router2.connectSemiDuplex(router3, null, LogLevel.ERROR)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isNotNull
        assertThat(router3.inboundMessages.poll(5, TimeUnit.SECONDS)).isNotNull
        assertThat(router1.inboundMessages).isEmpty()
        assertThat(router2.inboundMessages).isEmpty()
        assertThat(router3.inboundMessages).isEmpty()
    }

    @Test
    fun `2 receive routers in parallel`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()
        val router3 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR)
        router1.connectSemiDuplex(router3, null, LogLevel.ERROR)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isNotNull
        assertThat(router3.inboundMessages.poll(5, TimeUnit.SECONDS)).isNotNull
        assertThat(router1.inboundMessages).isEmpty()
        assertThat(router2.inboundMessages).isEmpty()
        assertThat(router3.inboundMessages).isEmpty()
    }

    @Test
    fun `2 receive routers all connected`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()
        val router3 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR)
        router1.connectSemiDuplex(router3, null, LogLevel.ERROR)
        router2.connectSemiDuplex(router3, null, LogLevel.ERROR)

        val msg = newMessage("topic1", 0L, "Hello".toByteArray())
        router1.router.publish(msg)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isNotNull
        assertThat(router3.inboundMessages.poll(5, TimeUnit.SECONDS)).isNotNull
        assertThat(router1.inboundMessages).isEmpty()
        assertThat(router2.inboundMessages).isEmpty()
        assertThat(router3.inboundMessages).isEmpty()
    }
}
