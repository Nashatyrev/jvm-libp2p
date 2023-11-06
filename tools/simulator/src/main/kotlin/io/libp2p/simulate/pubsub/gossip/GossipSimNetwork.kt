package io.libp2p.simulate.pubsub.gossip

import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.pubsub.SimPubsubConfig
import io.libp2p.simulate.pubsub.SimPubsubNetwork
import io.libp2p.simulate.pubsub.SimPubsubPeer
import io.libp2p.simulate.pubsub.SimPubsubPeerConfig
import io.libp2p.simulate.pubsub.SimPubsubRouterBuilder
import io.libp2p.simulate.pubsub.gossip.router.SimGossipRouterBuilder
import java.util.*

typealias GossipRouterBuilderFactory = (SimPeerId) -> SimGossipRouterBuilder

class GossipSimNetwork(
    cfg: GossipSimConfig,
    routerBuilderFactory: GossipRouterBuilderFactory = { SimGossipRouterBuilder() },
) : SimPubsubNetwork(cfg, routerBuilderFactory) {

    @Suppress("UNCHECKED_CAST")
    val gossipPeers: Map<SimPeerId, GossipSimPeer>
        get() = super.peers as Map<SimPeerId, GossipSimPeer>

    override fun createPeerInstance(
        simPeerId: Int,
        random: Random,
        simConfig: SimPubsubConfig,
        peerConfig: SimPubsubPeerConfig,
        routerBuilder: SimPubsubRouterBuilder
    ): SimPubsubPeer {
        routerBuilder as SimGossipRouterBuilder
        peerConfig as GossipSimPeerConfig
        routerBuilder.params = peerConfig.gossipParams
        routerBuilder.scoreParams = peerConfig.gossipScoreParams
        routerBuilder.additionalHeartbeatDelay = peerConfig.additionalHeartbeatDelay
        return GossipSimPeer(simPeerId, random, peerConfig.pubsubProtocol, routerBuilder as SimGossipRouterBuilder)
    }
}
