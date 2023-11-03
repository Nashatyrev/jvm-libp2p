package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.pubsub.gossip.BlocksTopic
import io.libp2p.simulate.pubsub.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.pubsub.gossip.Eth2DefaultScoreParams
import io.libp2p.simulate.pubsub.gossip.GossipSimConfig
import io.libp2p.simulate.pubsub.gossip.GossipSimNetwork
import io.libp2p.simulate.pubsub.gossip.GossipSimPeerConfigGenerator
import io.libp2p.simulate.pubsub.gossip.GossipSimulation
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.RandomNPeers
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

fun main() {
    GossipScoreTestSimulation().run()
}

class GossipScoreTestSimulation {

    fun run() {

        val simConfig = GossipSimConfig(
            GossipSimPeerConfigGenerator(
                topics = listOf(Topic(BlocksTopic)),
                gossipParams = Eth2DefaultGossipParams,
                gossipScoreParams = Eth2DefaultScoreParams,
                messageValidationDelays = RandomDistribution.const(50.milliseconds)
            ).generate(0, 1000),
            topology = RandomNPeers(30),
        )

        val simNetwork = GossipSimNetwork(simConfig)
        println("Creating peers...")
        simNetwork.createAllPeers()
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)

        for (j in 0..10) {
            for (i in 0..9) {
                simulation.publishMessage(j * i % simConfig.totalPeers)
                simulation.forwardTime(1.seconds)
            }
            val stats = getScoreStats(simNetwork).getDescriptiveStatistics()
            println(
                "" + j +
                    "\t" + stats.min +
                    "\t" + stats.getPercentile(5.0) +
                    "\t" + stats.mean + "" +
                    "\t" + stats.getPercentile(95.0) +
                    "\t" + stats.max
            )
        }
        println("Wrapping up...")
        simulation.forwardTime(10.seconds)

        println("Gathering results...")
        val results = simulation.gatherPubDeliveryStats()

        val msgDelayStats = StatsFactory.DEFAULT.createStats(results.deliveryDelays)

        val msgDeliveryStats = StatsFactory.DEFAULT.createStats("msgDelay").also { stats ->
            results.deliveries
                .groupingBy { it.initialPublishMsg.simMsgId }
                .eachCount()
                .values
                .forEach { stats += it.toDouble() / (simConfig.totalPeers - 1) }
        }

        println("Message delivery delay stats: $msgDelayStats")
        println("Ratio of messages delivered: $msgDeliveryStats")
        println("Gossip score stats: " + getScoreStats(simNetwork))
    }

    private fun getScoreStats(network: GossipSimNetwork) =
        StatsFactory.DEFAULT.createStats("gossipScore").also {
            it += network.gossipPeers.values
                .map { it.router }
                .flatMap { gossip ->
                    gossip.peers.map {
                        gossip.score.score(it.peerId)
                    }
                }
        }
}
