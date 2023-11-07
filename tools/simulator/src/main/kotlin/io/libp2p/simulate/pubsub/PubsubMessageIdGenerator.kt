package io.libp2p.simulate.stats.collect.gossip

import io.libp2p.simulate.stats.collect.PubsubMessageId
import pubsub.pb.Rpc

typealias PubsubMessageIdGenerator = (Rpc.Message) -> PubsubMessageId