package io.libp2p.simulate.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.erasure.ErasureRouter
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.router.AbstractMessagePeerHandler
import io.libp2p.pubsub.erasure.router.MessagePeerHandlerFactory
import io.libp2p.pubsub.erasure.router.SimpleMessagePeerHandler
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.pubsub.SimPubsubRouterBuilder
import java.util.Random
import java.util.concurrent.ScheduledExecutorService

class SimErasureRouterBuilder(
    val ackSendStrategy: (SampledMessage) -> AckSendStrategy,
    val sampleSendStrategy: (SampledMessage) -> SampleSendStrategy,
    val simErasureCoder: SimErasureCoder
) : SimPubsubRouterBuilder {

    override var name: String = ""
    override lateinit var scheduledAsyncExecutor: ScheduledExecutorService
    override lateinit var currentTimeSuppluer: CurrentTimeSupplier
    override lateinit var random: Random
    override var protocol: PubsubProtocol = PubsubProtocol.ErasureSub

    override fun build(): ErasureRouter {
        val simMessageRouterFactory = SimMessageRouterFactory(random, ackSendStrategy, sampleSendStrategy)
        return ErasureRouter(
            scheduledAsyncExecutor,
            simErasureCoder,
            simMessageRouterFactory
        )
    }
}

