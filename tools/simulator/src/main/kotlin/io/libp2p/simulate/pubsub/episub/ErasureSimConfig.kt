package io.libp2p.simulate.pubsub.episub

import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.pubsub.InOutBandwidth
import io.libp2p.simulate.pubsub.MessageValidation
import io.libp2p.simulate.pubsub.MessageValidationGenerator
import io.libp2p.simulate.pubsub.PubMessageGenerator
import io.libp2p.simulate.pubsub.SimPubsubConfig
import io.libp2p.simulate.pubsub.SimPubsubPeerConfig
import io.libp2p.simulate.pubsub.trickyPubSubMsgSizeEstimator
import io.libp2p.simulate.topology.RandomNPeers
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

data class ErasureSimPeerConfig(
    override val topics: List<Topic>,
    override val messageValidationGenerator: MessageValidationGenerator,
    override val bandwidth: InOutBandwidth,

    override val pubsubProtocol: PubsubProtocol = PubsubProtocol.ErasureSub,
) : SimPubsubPeerConfig

data class ErasureSimConfig(
    override val peerConfigs: List<ErasureSimPeerConfig>,

    override val messageGenerator: PubMessageGenerator = trickyPubSubMsgSizeEstimator(true),
    override val latency: LatencyDistribution = LatencyDistribution.createConst(ZERO),

    override val topology: Topology = RandomNPeers(10),
    override val warmUpDelay: Duration = 10.seconds,
    override val randomSeed: Long = 0,
) : SimPubsubConfig

data class ErasureSimPeerConfigGenerator(
    val topics: List<Topic>,
    val messageValidationDelays: RandomDistribution<Duration> = RandomDistribution.const(ZERO),

    // Network config
    val bandwidths: RandomDistribution<Bandwidth> = RandomDistribution.const(Bandwidth.UNLIM),
) {

    fun generate(randomSeed: Long): Sequence<ErasureSimPeerConfig> = sequence {
        val random = Random(randomSeed)
        val messageValidationDelaysValue = messageValidationDelays.newValue(random)
        val bandwidthsValue = bandwidths.newValue(random)
        while (true) {
            val msgValidationDelay = messageValidationDelaysValue.next()
            yield(
                ErasureSimPeerConfig(
                    topics,
                    { MessageValidation(msgValidationDelay, ValidationResult.Valid) },
                    InOutBandwidth(bandwidthsValue.next())
                )
            )
        }
    }

    fun generate(randomSeed: Long, count: Int): List<ErasureSimPeerConfig> = generate(randomSeed).take(count).toList()
}

