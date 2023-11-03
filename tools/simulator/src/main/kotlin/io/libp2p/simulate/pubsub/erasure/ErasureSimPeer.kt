package io.libp2p.simulate.pubsub.erasure

import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.simulate.pubsub.SimPubsubPeer
import io.libp2p.simulate.pubsub.erasure.router.SimErasureRouterBuilder
import java.util.*

class ErasureSimPeer(
    override val simPeerId: Int,
    override val random: Random,
    routerBuilder: SimErasureRouterBuilder
) : SimPubsubPeer(simPeerId, random, PubsubProtocol.ErasureSub, routerBuilder) {

}