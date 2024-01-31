package io.libp2p.simulate.stats.collect.pubsub.erasure

import io.libp2p.simulate.SimPeer
import io.libp2p.simulate.stats.collect.pubsub.PubsubMessageResult

fun PubsubMessageResult.peerSampleCounts(): Map<SimPeer, Int> =
    this.allPeers.associateWith { peer ->
        peerReceivedMessages[peer]!!.erasureSampleMessages.size
    }