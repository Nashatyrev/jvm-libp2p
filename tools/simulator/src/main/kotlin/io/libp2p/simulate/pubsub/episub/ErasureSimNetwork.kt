package io.libp2p.simulate.pubsub.episub

import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.pubsub.SimPubsubNetwork
import io.libp2p.simulate.pubsub.SimPubsubPeer
import io.libp2p.simulate.pubsub.SimPubsubPeerConfig
import io.libp2p.simulate.pubsub.SimPubsubRouterBuilder
import java.util.Random

typealias ErasureRouterBuilderFactory = (SimPeerId) -> SimErasureRouterBuilder
typealias ErasureSimPeerModifier = (SimPeerId, ErasureSimPeer) -> Unit

class ErasureSimNetwork(
    cfg: ErasureSimConfig,
    routerBuilderFactory: ErasureRouterBuilderFactory = { SimErasureRouterBuilder() },
) : SimPubsubNetwork(cfg, routerBuilderFactory) {

    override fun createPeerInstance(
        simPeerId: Int,
        random: Random,
        peerConfig: SimPubsubPeerConfig,
        routerBuilder: SimPubsubRouterBuilder
    ): SimPubsubPeer =
        ErasureSimPeer(simPeerId,random,routerBuilder as SimErasureRouterBuilder)
}
