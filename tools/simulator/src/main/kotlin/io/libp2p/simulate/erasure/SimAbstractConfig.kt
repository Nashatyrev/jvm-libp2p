package io.libp2p.simulate.erasure

import io.libp2p.core.pubsub.MessageApi
import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.topology.RandomNPeers
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

data class InOutBandwidth(
    val inbound: Bandwidth,
    val outbound: Bandwidth = inbound
)

data class MessageValidation(
    val validationDelay: Duration,
    val validationResult: ValidationResult
)

typealias MessageValidationGenerator = (MessageApi) -> MessageValidation

data class SimAbstractPeerConfig(
    // Gossip router config
    val pubsubProtocol: PubsubProtocol,

    // Gossip simulation config
    val topics: List<Topic>,
    val messageValidationGenerator: MessageValidationGenerator,

    // Other
    val bandwidth: InOutBandwidth,
)

data class SimAbstractConfig(
    val peerConfigs: List<SimAbstractPeerConfig>,

    val messageGenerator: ErasurePubMessageGenerator = trickyPubSubMsgSizeEstimator(true),
    val latency: LatencyDistribution = LatencyDistribution.createConst(ZERO),

    val topology: Topology = RandomNPeers(10),
    val warmUpDelay: Duration = 10.seconds,
    val randomSeed: Long = 0,
) {

    val totalPeers: Int get() = peerConfigs.size
}
