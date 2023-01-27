package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.etc.types.seconds
import io.libp2p.pubsub.gossip.GossipRouter
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.pubsub.gossip.builders.GossipScoreParamsBuilder
import io.libp2p.pubsub.gossip.builders.GossipTopicScoreParamsBuilder
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.gossip.BlocksTopic
import io.libp2p.simulate.gossip.Eth2DefaultBlockTopicParams
import io.libp2p.simulate.gossip.Eth2DefaultGossipParams
import io.libp2p.simulate.gossip.Eth2DefaultPeerScoreParams
import io.libp2p.simulate.gossip.Eth2DefaultScoreParams
import io.libp2p.simulate.gossip.Eth2DefaultTopicsParams
import io.libp2p.simulate.gossip.GossipSimConfig
import io.libp2p.simulate.gossip.GossipSimNetwork
import io.libp2p.simulate.gossip.GossipSimPeer
import io.libp2p.simulate.gossip.GossipSimulation
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.millis
import io.libp2p.tools.propertiesAsMap
import org.junit.jupiter.api.Test
import java.time.Duration

class Simulation2 {

    @Test
    fun sim() {

        val simConfig = GossipSimConfig(
            totalPeers = 1000,
            topics = listOf(Topic(BlocksTopic)),
            topology = RandomNPeers(30),
            gossipValidationDelay = 50.millis
        )

        val gossipParams = Eth2DefaultGossipParams
        val gossipScoreParams = Eth2DefaultScoreParams
        val gossipRouterCtor = { _: Int ->
            GossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
            }
        }

        // prints interchange of 2 peers
//        val peer_819 = PeerId.fromBase58("QmerDZ9nE6GJmXSbH3hYzptR1uae7JS2ZphU4kNwGEeDNg")
//        val peer_90 = PeerId.fromBase58("QmcWmvf2sRyh2DSHmEsu83GGxzQMn661uWdxJLcrvRzJ3H")
//        val simPeerModifier = { num :Int, peer: GossipSimPeer ->
//            if (num == 90) {
//                peer.pubsubLogs = { it == peer_819 }
//            }
//            if (num == 819) {
//                peer.pubsubLogs = { it == peer_90 }
//            }
//        }
        val simPeerModifier = { num: Int, peer: GossipSimPeer -> }

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
            println("" + j +
                    "\t" + stats.min +
                    "\t" + stats.getPercentile(5.0) +
                    "\t" + stats.mean + "" +
                    "\t" + stats.getPercentile(95.0) +
                    "\t" + stats.max)
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

//        val connections = simNetwork.peers.values.flatMap { peer ->
//            peer.getConnectedPeers().map { peer to it as GossipSimPeer }
//        }
//        val scoredConnections = connections.map { (from, to) ->
//            val gossipFrom = from.router as GossipRouter
//            val toPeerHandler = gossipFrom.peers.filter { it.peerId == to.peerId }.first()
//            Triple(gossipFrom.score.score(toPeerHandler), from, to)
//        }.sortedBy { it.first }

        println(msgDelayStats)
        println(msgDeliveryStats)
        println(getScoreStats(simNetwork))
    }

    fun getScoreStats(network: GossipSimNetwork) =
        StatsFactory.DEFAULT.createStats("gossipScore").also {
            it += network.peers.values
                .map { it.router as GossipRouter }
                .flatMap { gossip ->
                    gossip.peers.map {
                        gossip.score.score(it.peerId)
                    }
                }
        }

    @Test
    fun a() {
        fun iterateMeshMessageDeliveryWindow(values: List<Duration>) =
            values.map {
                GossipTopicScoreParamsBuilder(Eth2DefaultBlockTopicParams)
                    .meshMessageDeliveryWindow(it)
                    .build()
            }.map {
                Eth2DefaultTopicsParams.withTopic(BlocksTopic, it)
            }.map {
                GossipScoreParamsBuilder(Eth2DefaultScoreParams)
                    .peerScoreParams(Eth2DefaultPeerScoreParams)
                    .topicsScoreParams(it)
                    .build()
            }

        val param =
            iterateMeshMessageDeliveryWindow(listOf(20, 50, 100, 200, 500, 1000).map { it.millis })
        val map = param[0].propertiesAsMap()
        println(map)
    }
}