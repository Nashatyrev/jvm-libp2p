package io.libp2p.simulate.pubsub.gossip.router

import io.libp2p.core.crypto.sha256
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.AbstractPubsubMessage
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.pubsub.SimAbstractRouterBuilder
import pubsub.pb.Rpc
import kotlin.time.Duration

class SimGossipRouterBuilder : GossipRouterBuilder(), SimAbstractRouterBuilder {
    var serializeMessagesToBytes: Boolean = false
    var additionalHeartbeatDelay: Duration = Duration.ZERO

    var postModifier: (SimGossipRouterBuilder) -> Unit = { }

    init {
        messageFactory = { SimPubsubMessage(it) }
    }

    override fun createGossipRouter(): SimGossipRouter {
        val gossipScore =
            scoreFactory(scoreParams, scheduledAsyncExecutor, currentTimeSuppluer) { gossipRouterEventListeners += it }

        val router = SimGossipRouter(
            params = params,
            scoreParams = scoreParams,
            currentTimeSupplier = currentTimeSuppluer,
            random = random,
            name = name,
            mCache = mCache,
            score = gossipScore,
            chokeStrategy = chokeStrategy,
            subscriptionTopicSubscriptionFilter = subscriptionTopicSubscriptionFilter,
            protocol = protocol,
            executor = scheduledAsyncExecutor,
            messageFactory = messageFactory,
            seenMessages = seenCache,
            messageValidator = messageValidator,
            serializeToBytes = serializeMessagesToBytes,
            additionalHeartbeatDelay = additionalHeartbeatDelay
        )

        router.eventBroadcaster.listeners += gossipRouterEventListeners
        return router
    }

    class SimPubsubMessage(override val protobufMessage: Rpc.Message) : AbstractPubsubMessage() {
        override val messageId: MessageId =
            sha256(protobufMessage.data.toByteArray()).sliceArray(0..7).toWBytes()
    }

    override fun build(): SimGossipRouter {
        postModifier(this)
        return super.build() as SimGossipRouter
    }
}
