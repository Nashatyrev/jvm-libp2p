package io.libp2p.simulate.gossip

import io.libp2p.simulate.erasure.AbstractSimulation
import io.libp2p.simulate.erasure.SimAbstractConfig
import io.libp2p.simulate.erasure.SimAbstractNetwork
import io.libp2p.simulate.stats.collect.gossip.*
import java.util.concurrent.CompletableFuture

class GossipSimulation(
    cfg: GossipSimConfig,
    network: GossipSimNetwork
) : AbstractSimulation(cfg, network) {

    override val cfg: GossipSimConfig
        get() = super.cfg as GossipSimConfig
    override val network: GossipSimNetwork
        get() = super.network as GossipSimNetwork

    override val messageCollector = GossipMessageCollector(
        network.network,
        currentTimeSupplier,
        cfg.messageGenerator,
        anyPeer.getMessageIdGenerator()
    )

    fun gatherPubDeliveryStats(): GossipPubDeliveryResult =
        messageCollector.gatherResult().getGossipPubDeliveryResult()
}
