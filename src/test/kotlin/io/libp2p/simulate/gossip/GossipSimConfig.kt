package io.libp2p.simulate.gossip

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.Topology
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.MsgSizeEstimator
import io.libp2p.tools.millis
import io.libp2p.tools.seconds
import java.time.Duration

data class GossipSimConfig(
    val totalPeers: Int = 10000,
    val badPeers: Int = 0,

    val topic: Topic,

    val messageSizeEstimator: MsgSizeEstimator = GossipSimPeer.strictPubSubMsgSizeEstimator(true),

    val topology: Topology = RandomNPeers(10),
    val latency: RandomDistribution = RandomDistribution.const(1.0),
    val peersTimeShift: RandomDistribution = RandomDistribution.const(0.0),
    val gossipValidationDelay: Duration = 0.millis,

    val warmUpDelay: Duration = 5.seconds,
    val generatedNetworksCount: Int = 1,
    val sentMessageCount: Int = 10,
    val startRandomSeed: Long = 0,
    val iterationThreadsCount: Int = 1,
    val parallelIterationsCount: Int = 1,
)