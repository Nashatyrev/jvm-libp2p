package io.libp2p.simulate.pubsub.erasure

import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSelectionStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import io.libp2p.simulate.*
import io.libp2p.simulate.delay.bandwidth.AccurateBandwidthTracker
import io.libp2p.simulate.delay.bandwidth.BandwidthTrackerFactory
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.pubsub.InOutBandwidth
import io.libp2p.simulate.pubsub.MessageValidation
import io.libp2p.simulate.pubsub.MessageValidationGenerator
import io.libp2p.simulate.pubsub.PubsubMessageSizes
import io.libp2p.simulate.pubsub.SimPubsubConfig
import io.libp2p.simulate.pubsub.SimPubsubPeerConfig
import io.libp2p.simulate.pubsub.createGenericPubsubMessageSizes
import io.libp2p.simulate.pubsub.erasure.router.SimErasureCoder
import io.libp2p.simulate.pubsub.trickyMessageBodyGenerator
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

    override val pubsubMessageSizes: PubsubMessageSizes = trickyMessageBodyGenerator.createGenericPubsubMessageSizes(),
    override val latency: LatencyDistribution = LatencyDistribution.createConst(ZERO),
    override val bandwidthTrackerFactory: BandwidthTrackerFactory =
        BandwidthTrackerFactory.fromLambda(::AccurateBandwidthTracker),

    override val topology: Topology = RandomNPeers(10),
    override val warmUpDelay: Duration = 10.seconds,
    override val randomSeed: Long = 0,

    val ackSendStrategy: (SampledMessage) -> AckSendStrategy,
    val sampleSendStrategy: (SampledMessage) -> SampleSendStrategy,
    val sampleSelectionStrategy: (SampledMessage) -> SampleSelectionStrategy,
    val simErasureCoder: SimErasureCoder
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

