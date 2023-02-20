package io.libp2p.simulate.gossip.router

import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder

class SimGossipRouterBuilder : GossipRouterBuilder() {
    var serializeMessagesToBytes: Boolean = false

    override fun createGossipRouter(): GossipRouter {
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
            subscriptionTopicSubscriptionFilter = subscriptionTopicSubscriptionFilter,
            protocol = protocol,
            executor = scheduledAsyncExecutor,
            messageFactory = messageFactory,
            seenMessages = seenCache,
            messageValidator = messageValidator,
            serializeToBytes = serializeMessagesToBytes
        )
        router.heartbeatInitialDelay = heartbeatInitialDelay

        router.eventBroadcaster.listeners += gossipRouterEventListeners
        return router
    }
}