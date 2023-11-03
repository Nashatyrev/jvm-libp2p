package io.libp2p.simulate.pubsub.episub

import io.libp2p.simulate.SimPeerId
import io.libp2p.simulate.pubsub.SimAbstractNetwork
import io.libp2p.simulate.pubsub.SimAbstractPeer
import io.libp2p.simulate.pubsub.SimAbstractPeerConfig
import io.libp2p.simulate.pubsub.SimAbstractRouterBuilder
import java.util.Random

typealias ErasureRouterBuilderFactory = (SimPeerId) -> SimErasureRouterBuilder
typealias ErasureSimPeerModifier = (SimPeerId, ErasureSimPeer) -> Unit

class ErasureSimNetwork(
    cfg: ErasureSimConfig,
    routerBuilderFactory: ErasureRouterBuilderFactory = { SimErasureRouterBuilder() },
) : SimAbstractNetwork(cfg, routerBuilderFactory) {

    override fun createPeerInstance(
        simPeerId: Int,
        random: Random,
        peerConfig: SimAbstractPeerConfig,
        routerBuilder: SimAbstractRouterBuilder
    ): SimAbstractPeer =
        ErasureSimPeer(simPeerId,random,routerBuilder as SimErasureRouterBuilder)
}
