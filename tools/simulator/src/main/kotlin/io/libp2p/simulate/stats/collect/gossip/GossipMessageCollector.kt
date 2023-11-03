package io.libp2p.simulate.stats.collect.gossip

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.Network
import io.libp2p.simulate.pubsub.PubMessageGenerator
import io.libp2p.simulate.stats.collect.ConnectionsMessageCollector
import pubsub.pb.Rpc.RPC

class GossipMessageCollector(
    network: Network,
    timeSupplier: CurrentTimeSupplier,
    val msgGenerator: PubMessageGenerator,
    val pubsubMessageIdGenerator: PubsubMessageIdGenerator
) : ConnectionsMessageCollector<RPC>(network, timeSupplier) {

    fun gatherResult() =
        GossipMessageResult(deliveredMessages, msgGenerator, pubsubMessageIdGenerator)
}
