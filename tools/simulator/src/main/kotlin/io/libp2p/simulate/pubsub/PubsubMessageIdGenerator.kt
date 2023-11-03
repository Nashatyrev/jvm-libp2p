package io.libp2p.simulate.stats.collect.gossip

import pubsub.pb.Rpc

typealias PubsubMessageIdGenerator = (Rpc.Message) -> PubsubMessageId