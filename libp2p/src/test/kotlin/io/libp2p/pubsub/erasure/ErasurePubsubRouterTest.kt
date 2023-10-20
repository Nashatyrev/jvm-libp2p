package io.libp2p.pubsub.erasure

import io.libp2p.pubsub.DeterministicFuzz
import io.libp2p.pubsub.DeterministicFuzzRouterFactory
import io.libp2p.pubsub.PubsubRouterTest
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy.Companion.ALWAYS_RESPOND_TO_INBOUND_SAMPLES
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import io.libp2p.pubsub.flood.FloodRouter
import kotlin.random.Random

val random = Random(1)
val ackSendStrategy: () -> AckSendStrategy = { ALWAYS_RESPOND_TO_INBOUND_SAMPLES }
val sampleSendStrategy: () -> SampleSendStrategy = { SampleSendStrategy.sendAll() }

fun createErasureFuzzRouterFactory(): DeterministicFuzzRouterFactory =
    { executor, _, random ->

        val erasureCoder: ErasureCoder = TestErasureCoder(4, 4)
        val messageRouterFactory: MessageRouterFactory = TestMessageRouterFactory(random, ackSendStrategy, sampleSendStrategy)

        ErasureRouter(executor, erasureCoder, messageRouterFactory)
    }


class ErasurePubsubRouterTest(

) : PubsubRouterTest(createErasureFuzzRouterFactory()) {

}
