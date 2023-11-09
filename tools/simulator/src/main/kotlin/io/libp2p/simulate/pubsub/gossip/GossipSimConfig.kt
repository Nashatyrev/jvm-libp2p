package io.libp2p.simulate.pubsub.gossip

import io.libp2p.core.pubsub.Topic
import io.libp2p.core.pubsub.ValidationResult
import io.libp2p.pubsub.PubsubProtocol
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.GossipScoreParams
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
import io.libp2p.simulate.pubsub.trickyMessageBodyGenerator
import io.libp2p.simulate.topology.RandomNPeers
import java.util.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds

data class GossipSimPeerConfig(
    // Gossip router config
    override val pubsubProtocol: PubsubProtocol,
    val gossipParams: GossipParams,
    val gossipScoreParams: GossipScoreParams,
    val additionalHeartbeatDelay: Duration,

    // Gossip simulation config
    override val topics: List<Topic>,
    override val messageValidationGenerator: MessageValidationGenerator,

    // Other
    override val bandwidth: InOutBandwidth,
) : SimPubsubPeerConfig

data class GossipSimConfig(
    override val peerConfigs: List<GossipSimPeerConfig>,

    override val pubsubMessageSizes: PubsubMessageSizes = trickyMessageBodyGenerator.createGenericPubsubMessageSizes(),
    override val latency: LatencyDistribution = LatencyDistribution.createConst(ZERO),
    override val bandwidthTrackerFactory: BandwidthTrackerFactory =
        BandwidthTrackerFactory.fromLambda(::AccurateBandwidthTracker),

    override val topology: Topology = RandomNPeers(10),
    override val warmUpDelay: Duration = 10.seconds,
    override val randomSeed: Long = 0,
) : SimPubsubConfig

data class GossipSimPeerConfigGenerator(
    // Gossip router config
    val gossipProtocol: PubsubProtocol = PubsubProtocol.Gossip_V_1_1,
    val gossipParams: GossipParams = GossipParams(),
    val gossipScoreParams: GossipScoreParams = GossipScoreParams(),
    val additionalHeartbeatDelay: RandomDistribution<Duration> =
        RandomDistribution.uniform(0, gossipParams.heartbeatInterval.toMillis()).milliseconds(),

    // Gossip simulation config
    val topics: List<Topic>,
    val messageValidationDelays: RandomDistribution<Duration> = RandomDistribution.const(ZERO),

    // Network config
    val bandwidths: RandomDistribution<Bandwidth> = RandomDistribution.const(Bandwidth.UNLIM),
) {

    fun generate(randomSeed: Long): Sequence<GossipSimPeerConfig> = sequence {
        val random = Random(randomSeed)
        val additionalHeartbeatDelayValue = additionalHeartbeatDelay.newValue(random)
        val messageValidationDelaysValue = messageValidationDelays.newValue(random)
        val bandwidthsValue = bandwidths.newValue(random)
        while (true) {
            val msgValidationDelay = messageValidationDelaysValue.next()
            yield(
                GossipSimPeerConfig(
                    gossipProtocol,
                    gossipParams,
                    gossipScoreParams,
                    additionalHeartbeatDelayValue.next(),
                    topics,
                    { MessageValidation(msgValidationDelay, ValidationResult.Valid) },
                    InOutBandwidth(bandwidthsValue.next())
                )
            )
        }
    }

    fun generate(randomSeed: Long, count: Int): List<GossipSimPeerConfig> = generate(randomSeed).take(count).toList()
}

