package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.delay.latency.ClusteredNodesConfig
import io.libp2p.simulate.delay.latency.aws.AwsLatencies
import io.libp2p.simulate.delay.latency.aws.AwsRegion.*
import io.libp2p.simulate.pubsub.gossip.GossipSimConfig
import io.libp2p.simulate.pubsub.gossip.GossipSimNetwork
import io.libp2p.simulate.pubsub.gossip.GossipSimPeerConfigGenerator
import io.libp2p.simulate.pubsub.gossip.GossipSimulation
import io.libp2p.simulate.stats.getStats
import io.libp2p.simulate.stats.toLongDescrString
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.log
import java.util.Random

fun main() {
    AwsPeersSimulation().run()
}

class AwsPeersSimulation(
    val logger: (String) -> Unit = { log(it) },

    val clusteredNodesConfig: ClusteredNodesConfig<*> = ClusteredNodesConfig(
        RandomDistribution.discreteEven(
            EU_NORTH_1 to 50,
            EU_CENTRAL_1 to 50,
            EU_WEST_1 to 50,
            EU_WEST_2 to 50,
            AP_NORTHEAST_1 to 50,
            AP_NORTHEAST_2 to 50,
            AP_SOUTHEAST_1 to 50,
            AP_SOUTHEAST_2 to 50,
            AP_SOUTH_1 to 50,
            SA_EAST_1 to 50,
            CA_CENTRAL_1 to 50,
            US_EAST_1 to 50,
            US_EAST_2 to 50,
            US_WEST_1 to 50,
            US_WEST_2 to 50,
        ).newValue(Random(0)),
        { c1, c2 ->
            AwsLatencies.SAMPLE.getLatency(c1, c2)
        },
        5
    ),
    val nodePeerCount: Int = 10,
    val testTopic: Topic = Topic("Topic-1"),
    val messageSize: Int = 32 * 1024,

    val sendingPeersCount: Int = 50,
    val messagesPerPeerCount: Int = 5,

    val randomSeed: Long = 0,

    val simConfig: GossipSimConfig = GossipSimConfig(
        peerConfigs = GossipSimPeerConfigGenerator(
            topics = listOf(testTopic),
        ).generate(randomSeed, 750),
        topology = RandomNPeers(nodePeerCount),
//        messageValidationGenerator = constantValidationGenerator(10.milliseconds),
//        bandwidthGenerator = constantBandwidthGenerator(Bandwidth.mbitsPerSec(100)),
        latency = clusteredNodesConfig.latencyDistribution
    )
) {

    data class RunResult(
        val delaysBySendingPeer: Map<Int, List<List<Long>>>
    )

    val simulation by lazy {
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
//        logger("Sending message at time ${simulation.network.timeController.time}...")
        simulation.publishMessage(publisherPeer, messageSize, testTopic)

        simulation.forwardTimeUntilAllPubDelivered()
//        logger("All messages delivered at time ${simulation.network.timeController.time}")
        simulation.forwardTimeUntilNoPendingMessages()
//        logger("No more pending messages at time ${simulation.network.timeController.time}")
    }

    fun gatherDelayResults(): List<Long> {
        val ret = simulation.gatherPubDeliveryStats().deliveryDelays
        simulation.clearAllMessages()
        return ret
    }

    fun runPublishingPeer(publisherPeer: Int = 0): List<List<Long>> {
        return List(messagesPerPeerCount) {
            publishMessage(publisherPeer)
            val delays = gatherDelayResults()
            logger("From [$publisherPeer], message #$it, deliver stats: " + delays.getStats().toLongDescrString())
            delays
        }
    }

    fun runAndCollectResults(): RunResult {
        return RunResult(
            List(sendingPeersCount) {
                it to runPublishingPeer(it)
            }.toMap()
        )
    }

    fun run() {
        runAndCollectResults()
    }
}