package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.seconds
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.gossip.router.SimGossipRouterBuilder
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.millis

fun main() {
    GossipScoreTestSimulation().run()
}

class GossipScoreTestSimulation {

    fun run() {

        val simConfig = GossipSimConfig(
            totalPeers = 1000,
            topics = listOf(Topic(BlocksTopic)),
            topology = RandomNPeers(30),
            gossipValidationDelay = 50.millis
        )

        val gossipParams = Eth2DefaultGossipParams
        val gossipScoreParams = Eth2DefaultScoreParams
        val gossipRouterCtor = { _: Int ->
            SimGossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
            }
        }

        val simPeerModifier = { _: Int, _: GossipSimPeer -> }

        val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor, simPeerModifier)
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
        val results = simulation.gatherMessageResults()

        val msgDelayStats = StatsFactory.DEFAULT.createStats("msgDelay").also {
            it += results.entries.flatMap { e ->
                e.value.map { it.receivedTime - e.key.sentTime }
            }
        }
        val msgDeliveryStats = StatsFactory.DEFAULT.createStats("msgDelay").also {
            it += results.entries.map { it.value.size.toDouble() / (simConfig.totalPeers - 1) }
        }

        println("Message delivery delay stats: $msgDelayStats")
        println("Ratio of messages delivered: $msgDeliveryStats")
        println("Gossip score stats: " + getScoreStats(simNetwork))
    }

    private fun getScoreStats(network: GossipSimNetwork) =
        StatsFactory.DEFAULT.createStats("gossipScore").also {
            it += network.peers.values
                .map { it.router }
                .flatMap { gossip ->
                    gossip.peers.map {
                        gossip.score.score(it.peerId)
                    }
                }
        }
}