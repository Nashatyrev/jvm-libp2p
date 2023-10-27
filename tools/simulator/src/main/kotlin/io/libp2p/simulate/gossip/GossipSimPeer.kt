package io.libp2p.simulate.gossip

import io.libp2p.core.PeerId
import io.libp2p.core.Stream
import io.libp2p.core.pubsub.createPubsubApi
import io.libp2p.etc.types.lazyVar
import io.libp2p.pubsub.AbstractRouter
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.PubsubRouterDebug
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.simulate.erasure.SimAbstractPeer
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stream.StreamSimPeer
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import java.util.*
import java.util.concurrent.CompletableFuture

class GossipSimPeer(
    override val simPeerId: Int,
    override val random: Random,
    protocol: PubsubProtocol,
    routerBuilder: SimGossipRouterBuilder
) : SimAbstractPeer(simPeerId, random, protocol, routerBuilder) {

    override val router: GossipRouter
        get() = super.router as GossipRouter
}