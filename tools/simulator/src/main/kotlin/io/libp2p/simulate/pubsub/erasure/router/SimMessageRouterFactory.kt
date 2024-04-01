package io.libp2p.simulate.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.router.AbstractMessagePeerHandler
import io.libp2p.pubsub.erasure.router.MessagePeerHandlerFactory
import io.libp2p.pubsub.erasure.router.MessageRouter
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import io.libp2p.pubsub.erasure.router.RelayingMessageRouter
import io.libp2p.pubsub.erasure.router.SimpleMessagePeerHandler
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSelectionStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import java.util.Random

class SimMessageRouterFactory(
    val random: Random,
    val ackSendStrategy: (SampledMessage) -> AckSendStrategy,
    val sampleSendStrategy: (SampledMessage) -> SampleSendStrategy,
    val sampleSelectionStrategyFact: (SampledMessage) -> SampleSelectionStrategy
) : MessageRouterFactory() {

    override fun create(message: MutableSampledMessage, peers: List<PeerId>): MessageRouter =
        RelayingMessageRouter(sender, peers, message, random, TestMessagePeerHandlerFactory(message))

    inner class TestMessagePeerHandlerFactory(message: SampledMessage) : MessagePeerHandlerFactory() {
        val sampleSelectionStrategy = sampleSelectionStrategyFact(message)
        override fun create(message: MutableSampledMessage, peer: PeerId): AbstractMessagePeerHandler {
            return SimpleMessagePeerHandler(
                message,
                peer,
                sender,
                random,
                ackSendStrategy(message),
                sampleSendStrategy(message),
                sampleSelectionStrategy
            )
        }
    }
}