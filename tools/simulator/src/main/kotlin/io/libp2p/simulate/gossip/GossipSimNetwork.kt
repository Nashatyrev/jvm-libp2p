package io.libp2p.simulate.gossip

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.simulate.Network
import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.delay.bandwidth.AccurateBandwidthTracker
import io.libp2p.simulate.erasure.AbstractSimPeerModifier
import io.libp2p.simulate.erasure.SimAbstractNetwork
import io.libp2p.simulate.erasure.SimAbstractPeer
import io.libp2p.simulate.erasure.SimAbstractPeerConfig
import io.libp2p.simulate.erasure.router.SimAbstractRouterBuilder
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.tools.schedulers.ControlledExecutorServiceImpl
import io.libp2p.tools.schedulers.TimeControllerImpl
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
