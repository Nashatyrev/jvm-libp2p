package io.libp2p.pubsub.erasure

import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toProtobuf
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.DefaultPubsubMessage
import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.DeterministicFuzzRouterFactory
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.PubsubRouterDebug
import io.libp2p.pubsub.PubsubRouterTest
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy.Companion.ALWAYS_RESPOND_TO_INBOUND_SAMPLES
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.tools.schedulers.TimeController
import io.netty.handler.logging.LogLevel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pubsub.pb.Rpc
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

val random = Random(1)
val sampleSize = 4
val ackSendStrategy: () -> AckSendStrategy = { ALWAYS_RESPOND_TO_INBOUND_SAMPLES }
//val sampleSendStrategy: () -> SampleSendStrategy = { SampleSendStrategy.sendAll() }
val sampleSendStrategy: () -> SampleSendStrategy = { SampleSendStrategy.cWndStrategy(2) }

fun createErasureFuzzRouterFactory(): DeterministicFuzzRouterFactory =
    object : DeterministicFuzzRouterFactory {
        private var counter = 0
        override fun invoke(
            executor: ScheduledExecutorService,
            p2: CurrentTimeSupplier,
            random: java.util.Random
        ): PubsubRouterDebug {
            val erasureCoder: ErasureCoder = TestErasureCoder(sampleSize, 4)
            val messageRouterFactory: MessageRouterFactory = TestMessageRouterFactory(random, ackSendStrategy, sampleSendStrategy)

            val erasureRouter = ErasureRouter(executor, erasureCoder, messageRouterFactory)
            erasureRouter.name = "$counter"
            counter++
            return erasureRouter
        }

    }


class ErasurePubsubRouterTest(
) {
    val routerFactory: DeterministicFuzzRouterFactory = createErasureFuzzRouterFactory()
    val topic = "topic1"
    val fuzz = DeterministicFuzz()
    val latency: Duration = 10.milliseconds

    private fun createRouterAndSubscribe() =
        fuzz.createTestRouter(routerFactory).also {
            it.router.subscribe(topic)
//            it.pubsubLogWritesOnly = true
        }

    fun newMessage(topic: String, messageId: ByteArray, data: ByteArray) =
        ErasureRouter.ErasurePubsubMessage(
            messageId.toWBytes(),
            topic,
            data.toWBytes()
        )

    fun createMessage(samplesCount: Int, payload: Int): PubsubMessage {
        val payloadBytes = payload.toBytesBigEndian()
        return newMessage(topic, payloadBytes, ByteArray(sampleSize * samplesCount) { payloadBytes[it % 4] })
    }

    @Test
    fun `simple case`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR)

        val msg = createMessage(3, 0x77)
        router1.router.publish(msg)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
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

        val msg = createMessage(3, 0x77)
        router1.router.publish(msg)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        assertThat(router3.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
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

        val msg = createMessage(3, 0x77)
        router1.router.publish(msg)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        assertThat(router3.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        assertThat(router1.inboundMessages).isEmpty()
        assertThat(router2.inboundMessages).isEmpty()
        assertThat(router3.inboundMessages).isEmpty()
    }

    @Test
    fun `2 receive routers all connected`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()
        val router3 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR).setLatency(10.milliseconds)
        router1.connectSemiDuplex(router3, null, LogLevel.ERROR).setLatency(10.milliseconds)
        router2.connectSemiDuplex(router3, null, LogLevel.ERROR).setLatency(10.milliseconds)

        val msg = createMessage(3, 0x77)
        router1.router.publish(msg)

        fuzz.timeController.addTime(1.days)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        assertThat(router3.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        assertThat(router1.inboundMessages).isEmpty()
        assertThat(router2.inboundMessages).isEmpty()
        assertThat(router3.inboundMessages).isEmpty()
    }
}

fun TimeController.addTime(kDuration: Duration) { this.addTime(kDuration.toJavaDuration()) }