package io.libp2p.simulate.main.sample

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.pubsub.gossip.GossipSimConfig
import io.libp2p.simulate.pubsub.gossip.GossipSimNetwork
import io.libp2p.simulate.pubsub.gossip.GossipSimPeerConfigGenerator
import io.libp2p.simulate.pubsub.gossip.GossipSimulation
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.log
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    SimpleSimulation().publishMessage()
}

class SimpleSimulation(
    val logger: (String) -> Unit = { log(it) },
    nodeCount: Int = 1000,
    nodePeerCount: Int = 30,
    val testTopic: Topic = Topic("Topic-1"),
    val messageSize: Int = 32 * 1024,

    val simConfig: GossipSimConfig = GossipSimConfig(
        peerConfigs = GossipSimPeerConfigGenerator(
            topics = listOf(testTopic),
            gossipParams = GossipParams(),
            bandwidths = RandomDistribution.const(Bandwidth.mbitsPerSec(100)),
            messageValidationDelays = RandomDistribution.const(10.milliseconds)
        ).generate(0, nodeCount),
        topology = RandomNPeers(nodePeerCount),
        latency = LatencyDistribution.createConst(50.milliseconds),
    )
) {

    val simulation = run {
        val simNetwork = GossipSimNetwork(simConfig)
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()
        logger("Peers connected. Graph diameter is " + simNetwork.network.topologyGraph.calcDiameter())

        logger("Creating simulation...")
        GossipSimulation(simConfig, simNetwork)
    }

    fun publishMessage(publisherPeer: Int = 0) {
        logger("Sending message at time ${simulation.network.timeController.time}...")
        simulation.publishMessage(publisherPeer, messageSize, testTopic)

        simulation.forwardTimeUntilAllPubDelivered()
        logger("All messages delivered at time ${simulation.network.timeController.time}")
        simulation.forwardTimeUntilNoPendingMessages()
        logger("No more pending messages at time ${simulation.network.timeController.time}")

        val messagesResult = simulation.messageCollector.gatherResult()
        logger("Network statistics: messages: ${messagesResult.getTotalMessageCount()}, traffic: ${messagesResult.getTotalTraffic()}")

        val deliveryStats = simulation.gatherPubDeliveryStats()
        val deliveryAggrStats = StatsFactory.DEFAULT.createStats(deliveryStats.deliveryDelays)
        logger("Delivery stats: $deliveryAggrStats")
    }
}
