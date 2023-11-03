package io.libp2p.simulate.pubsub.gossip

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.pubsub.AbstractSimPeerModifier
import io.libp2p.simulate.pubsub.SimAbstractNetwork
import io.libp2p.simulate.pubsub.SimAbstractPeer
import io.libp2p.simulate.pubsub.SimAbstractPeerConfig
import io.libp2p.simulate.pubsub.SimAbstractRouterBuilder
import io.libp2p.simulate.pubsub.gossip.router.SimGossipRouterBuilder
import java.util.*

typealias GossipRouterBuilderFactory = (SimPeerId) -> SimGossipRouterBuilder

class GossipSimNetwork(
    cfg: GossipSimConfig,
    routerBuilderFactory: GossipRouterBuilderFactory = { SimGossipRouterBuilder() },
) : SimAbstractNetwork(cfg, routerBuilderFactory) {

    @Suppress("UNCHECKED_CAST")
    val gossipPeers: Map<SimPeerId, GossipSimPeer>
        get() = super.peers as Map<SimPeerId, GossipSimPeer>

    override fun createPeerInstance(
        simPeerId: Int,
        random: Random,
        peerConfig: SimAbstractPeerConfig,
        routerBuilder: SimAbstractRouterBuilder
    ): SimAbstractPeer {
        routerBuilder as SimGossipRouterBuilder
        peerConfig as GossipSimPeerConfig
        routerBuilder.params = peerConfig.gossipParams
        routerBuilder.scoreParams = peerConfig.gossipScoreParams
        routerBuilder.additionalHeartbeatDelay = peerConfig.additionalHeartbeatDelay
        return GossipSimPeer(simPeerId, random, peerConfig.pubsubProtocol, routerBuilder as SimGossipRouterBuilder)
    }
}
