package io.libp2p.pubsub.erasure

import io.libp2p.etc.types.toBytesBigEndian
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.DeterministicFuzzRouterFactory
import io.libp2p.pubsub.PubsubMessage
import io.libp2p.pubsub.PubsubRouterDebug
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy.Companion.ALWAYS_ON_NEW_SAMPLES
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy.Companion.ALWAYS_RESPOND_TO_INBOUND_SAMPLES
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.tools.schedulers.TimeController
import io.netty.handler.logging.LogLevel
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

class ErasureFuzzRouterFactory(
    val sampleSize: Int,
    val extensionFactor: Int,
    val ackSendStrategy: (SampledMessage) -> AckSendStrategy,
    val sampleSendStrategy: (SampledMessage) -> SampleSendStrategy,
    val random: Random = Random(1)
) : DeterministicFuzzRouterFactory {

    private var counter = 0

    override fun create(
        executor: ScheduledExecutorService,
        timeSupplier: CurrentTimeSupplier,
        random: java.util.Random
    ): PubsubRouterDebug {
        val erasureCoder: ErasureCoder = TestErasureCoder(sampleSize, extensionFactor)
        val messageRouterFactory: MessageRouterFactory = TestMessageRouterFactory(random, ackSendStrategy, sampleSendStrategy)

        val erasureRouter = ErasureRouter(executor, erasureCoder, messageRouterFactory)
        erasureRouter.name = "$counter"
        counter++
        return erasureRouter
    }
}

class ErasurePubsubRouterTest(
) {
    val erasure = ErasureFuzzRouterFactory(
        sampleSize = 4,
        extensionFactor = 4,
//        ackSendStrategy = { ALWAYS_RESPOND_TO_INBOUND_SAMPLES },
        ackSendStrategy = AckSendStrategy.Companion::allInboundAndWhenComplete,
        //val sampleSendStrategy: () -> SampleSendStrategy = { SampleSendStrategy.sendAll() }
        sampleSendStrategy = { SampleSendStrategy.cWndStrategy(2) },
    )

    val topic = "topic1"
    val fuzz = DeterministicFuzz()

    private fun createRouterAndSubscribe(
        erasureRouterFactory: ErasureFuzzRouterFactory = erasure
    ) =
        fuzz.createTestRouter(erasureRouterFactory).also {
            it.router.subscribe(topic)
            it.pubsubLogWritesOnly = true
            it.simTimeSupplier = { fuzz.timeController.time }
        }

    fun createMessage(samplesCount: Int, payload: Int): PubsubMessage {
        val payloadBytes = payload.toBytesBigEndian()
        return newMessage(
            topic,
            payloadBytes,
            ByteArray(erasure.sampleSize * samplesCount) { payloadBytes[it % 4] })
    }

    @Test
    fun `simple case`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR)
            .setLatency(10.milliseconds)

        val msg = createMessage(3, 0x77)
        router1.router.publish(msg)

        fuzz.timeController.addTime(1.days)

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
    fun `3 routers all connected`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()
        val router3 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR).setLatency(10.milliseconds)
        router1.connectSemiDuplex(router3, null, LogLevel.ERROR).setLatency(100.milliseconds)
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

    @Test
    fun `4 routers romb connected`() {
        val router1 = createRouterAndSubscribe()
        val router2 = createRouterAndSubscribe()
        val router3 = createRouterAndSubscribe()
        val router4 = createRouterAndSubscribe()

        router1.connectSemiDuplex(router2, null, LogLevel.ERROR).setLatency(10.milliseconds)
        router1.connectSemiDuplex(router3, null, LogLevel.ERROR).setLatency(10.milliseconds)
        router2.connectSemiDuplex(router4, null, LogLevel.ERROR).setLatency(10.milliseconds)
        router3.connectSemiDuplex(router4, null, LogLevel.ERROR).setLatency(10.milliseconds)

        val msg = createMessage(30, 0x77)
        router1.router.publish(msg)

        fuzz.timeController.addTime(1.days)

        assertThat(router2.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        assertThat(router3.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        assertThat(router4.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        assertThat(router1.inboundMessages).isEmpty()
        assertThat(router2.inboundMessages).isEmpty()
        assertThat(router3.inboundMessages).isEmpty()
        assertThat(router4.inboundMessages).isEmpty()
    }

    private fun allToAllPairs(size: Int): List<Pair<Int, Int>> =
        sequence {
            for (i in 0 until size - 1) {
                for (j in i + 1 until size) {
                    yield(i to j)
                }
            }
        }.toList()

    @Test
    fun `10 routers all connected`() {
        val random = Random(1)
        val routers = List(10) { createRouterAndSubscribe() }

        val connections = allToAllPairs(routers.size)
            .map {
                routers[it.first].connectSemiDuplex(routers[it.second], null, LogLevel.ERROR)
                    .setLatency(random.nextInt(1, 20).milliseconds)
            }

        val msg = createMessage(60, 0x77)
        routers[0].router.publish(msg)

        fuzz.timeController.addTime(1.days)

        routers.drop(1).forEach {
            assertThat(it.inboundMessages.poll(5, TimeUnit.SECONDS)).isEqualTo(msg)
        }
        routers.forEach {
            assertThat(it.inboundMessages).isEmpty()
        }
    }


    companion object {
        fun newMessage(topic: String, messageId: ByteArray, data: ByteArray) =
            ErasureRouter.ErasurePubsubMessage(
                messageId.toWBytes(),
                topic,
                data.toWBytes()
            )
    }
}

fun TimeController.addTime(kDuration: Duration) { this.addTime(kDuration.toJavaDuration()) }