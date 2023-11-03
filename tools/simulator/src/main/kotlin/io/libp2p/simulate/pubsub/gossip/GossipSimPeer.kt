package io.libp2p.simulate.pubsub.gossip

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.simulate.pubsub.SimAbstractPeer
import io.libp2p.simulate.pubsub.gossip.router.SimGossipRouterBuilder
import java.util.*

class GossipSimPeer(
    override val simPeerId: Int,
    override val random: Random,
    protocol: PubsubProtocol,
    routerBuilder: SimGossipRouterBuilder
) : SimAbstractPeer(simPeerId, random, protocol, routerBuilder) {

    override val router: GossipRouter
        get() = super.router as GossipRouter
}