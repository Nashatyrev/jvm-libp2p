package io.libp2p.simulate.pubsub.gossip

import io.libp2p.simulate.pubsub.PubsubSimulation
import io.libp2p.simulate.stats.collect.pubsub.gossip.GossipPubDeliveryResult
import io.libp2p.simulate.stats.collect.pubsub.gossip.getGossipPubDeliveryResult

class GossipSimulation(
    cfg: GossipSimConfig,
    network: GossipSimNetwork
) : PubsubSimulation(cfg, network) {

    override val cfg: GossipSimConfig
        get() = super.cfg as GossipSimConfig
    override val network: GossipSimNetwork
        get() = super.network as GossipSimNetwork

    init {
        start()
    }

    fun gatherPubDeliveryStats(): GossipPubDeliveryResult =
        messageCollector.gatherResult().getGossipPubDeliveryResult()
}
