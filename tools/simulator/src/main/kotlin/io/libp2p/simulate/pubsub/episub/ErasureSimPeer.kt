package io.libp2p.simulate.pubsub.episub

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.simulate.pubsub.SimAbstractPeer
import io.libp2p.simulate.pubsub.gossip.router.SimGossipRouterBuilder
import java.util.*

class ErasureSimPeer(
    override val simPeerId: Int,
    override val random: Random,
    routerBuilder: SimErasureRouterBuilder
) : SimAbstractPeer(simPeerId, random, PubsubProtocol.ErasureSub, routerBuilder) {

}