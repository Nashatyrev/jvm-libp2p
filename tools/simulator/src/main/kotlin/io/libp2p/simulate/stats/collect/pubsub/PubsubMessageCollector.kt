package io.libp2p.simulate.stats.collect.pubsub

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.Network
import io.libp2p.simulate.pubsub.PubsubMessageSizes
import io.libp2p.simulate.stats.collect.ConnectionsMessageCollector
import io.libp2p.simulate.stats.collect.gossip.PubsubMessageIdGenerator
import pubsub.pb.Rpc.RPC

class PubsubMessageCollector(
    network: Network,
    timeSupplier: CurrentTimeSupplier,
    val msgGenerator: PubsubMessageSizes,
    val pubsubMessageIdGenerator: PubsubMessageIdGenerator
) : ConnectionsMessageCollector<RPC>(network, timeSupplier) {

    fun gatherResult() =
        PubsubMessageResult(deliveredMessages, msgGenerator, pubsubMessageIdGenerator)
}
