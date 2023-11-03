package io.libp2p.simulate.pubsub.episub

import io.libp2p.simulate.pubsub.AbstractSimulation
import io.libp2p.simulate.stats.collect.ConnectionsMessageCollector
import io.libp2p.simulate.stats.collect.gossip.*
import pubsub.pb.Rpc.RPC

class ErasureSimulation(
    cfg: ErasureSimConfig,
    network: ErasureSimNetwork
) : AbstractSimulation(cfg, network) {

    override val messageCollector = ConnectionsMessageCollector<RPC>(
        network.network,
        currentTimeSupplier
    )

    init {
        start()
    }
}
