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
typealias GossipSimPeerModifier = (SimPeerId, GossipSimPeer) -> Unit

class GossipSimNetwork(
    cfg: GossipSimConfig,
    routerBuilderFactory: GossipRouterBuilderFactory = { SimGossipRouterBuilder() },
    simPeerModifier: AbstractSimPeerModifier = { _, _ -> }
) : SimAbstractNetwork(cfg, routerBuilderFactory, simPeerModifier) {

    @Suppress("UNCHECKED_CAST")
    val gossipPeers: Map<SimPeerId, GossipSimPeer>
        get() = super.peers as Map<SimPeerId, GossipSimPeer>

    override fun alterRouterBuilder(builder: SimAbstractRouterBuilder, peerConfig: SimAbstractPeerConfig) {
        builder as SimGossipRouterBuilder
        peerConfig as GossipSimPeerConfig
        builder.params = peerConfig.gossipParams
        builder.scoreParams = peerConfig.gossipScoreParams
        builder.additionalHeartbeatDelay = peerConfig.additionalHeartbeatDelay
    }

    override fun createPeerInstance(
        simPeerId: Int,
        random: Random,
        protocol: PubsubProtocol,
        routerBuilder: SimAbstractRouterBuilder
    ): SimAbstractPeer =
        GossipSimPeer(simPeerId,random,protocol, routerBuilder as SimGossipRouterBuilder)
}
