package io.libp2p.simulate.pubsub.erasure

import io.libp2p.simulate.pubsub.PubsubSimulation
import io.libp2p.simulate.stats.collect.ConnectionsMessageCollector
import pubsub.pb.Rpc.RPC

class ErasureSimulation(
    cfg: ErasureSimConfig,
    network: ErasureSimNetwork
) : PubsubSimulation(cfg, network) {

    init {
        start()
    }
}
