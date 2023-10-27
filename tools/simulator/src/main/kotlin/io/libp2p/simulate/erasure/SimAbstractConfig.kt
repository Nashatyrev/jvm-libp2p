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

interface SimAbstractPeerConfig {
    // Gossip router config
    val pubsubProtocol: PubsubProtocol

    // Gossip simulation config
    val topics: List<Topic>
    val messageValidationGenerator: MessageValidationGenerator

    // Other
    val bandwidth: InOutBandwidth
}

interface SimAbstractConfig {
    val peerConfigs: List<SimAbstractPeerConfig>

    val messageGenerator: PubMessageGenerator
    val latency: LatencyDistribution

    val topology: Topology
    val warmUpDelay: Duration
    val randomSeed: Long

    val totalPeers: Int get() = peerConfigs.size
}
