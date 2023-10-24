package io.libp2p.pubsub.erasure

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.isComplete
import io.libp2p.pubsub.erasure.router.AbstractMessagePeerHandler
import io.libp2p.pubsub.erasure.router.MessagePeerHandlerFactory
import io.libp2p.pubsub.erasure.router.MessageRouter
import io.libp2p.pubsub.erasure.router.MessageRouterFactory
import io.libp2p.pubsub.erasure.router.PublishingMessageRouter
import io.libp2p.pubsub.erasure.router.RelayingMessageRouter
import io.libp2p.pubsub.erasure.router.SimpleMessagePeerHandler
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import java.util.Random

class TestMessageRouterFactory(
    val random: Random,
    val ackSendStrategy: () -> AckSendStrategy,
    val sampleSendStrategy: () -> SampleSendStrategy
) : MessageRouterFactory() {

    override fun create(message: MutableSampledMessage, peers: List<PeerId>): MessageRouter =
        RelayingMessageRouter(sender, peers, message, random, TestMessagePeerHandlerFactory())

    inner class TestMessagePeerHandlerFactory : MessagePeerHandlerFactory() {
        override fun create(message: MutableSampledMessage, peer: PeerId): AbstractMessagePeerHandler {
            return SimpleMessagePeerHandler(message, peer, sender, random, ackSendStrategy(), sampleSendStrategy())
        }
    }
}