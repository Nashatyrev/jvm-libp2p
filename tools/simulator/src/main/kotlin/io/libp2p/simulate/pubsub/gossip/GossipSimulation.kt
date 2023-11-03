package io.libp2p.simulate.pubsub.gossip

import io.libp2p.simulate.pubsub.AbstractSimulation
import io.libp2p.simulate.stats.collect.gossip.*

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

    init {
        start()
    }

    fun gatherPubDeliveryStats(): GossipPubDeliveryResult =
        messageCollector.gatherResult().getGossipPubDeliveryResult()
}
