package io.libp2p.simulate.pubsub.gossip

import io.libp2p.simulate.pubsub.PubsubSimulation
import io.libp2p.simulate.pubsub.getMessageIdGenerator
import io.libp2p.simulate.stats.collect.gossip.*

class GossipSimulation(
    cfg: GossipSimConfig,
    network: GossipSimNetwork
) : PubsubSimulation(cfg, network) {

    override val cfg: GossipSimConfig
        get() = super.cfg as GossipSimConfig
    override val network: GossipSimNetwork
        get() = super.network as GossipSimNetwork

    private val anyPeer get() = network.peers.values.first()
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
