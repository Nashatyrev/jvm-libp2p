package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.SequentialBandwidthTracker
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.topology.CustomTopology
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.millis
import io.libp2p.tools.minutes
import org.junit.jupiter.api.Test

class BlobDecouplingSimulation {

    data class PeerBandwidthValue(
        val inbound: Bandwidth,
        val outbound: Bandwidth
    )

    val peerBandwidths: (GossipSimPeer) -> PeerBandwidthValue = {
        PeerBandwidthValue(Bandwidth.mbitsPerSec(10), Bandwidth.mbitsPerSec(10))
    }
    val bandwidthFactory: (PeerBandwidthValue, GossipSimPeer) -> PeerBandwidth = { band, peer ->
        PeerBandwidth(
            SequentialBandwidthTracker(band.inbound, peer.simExecutor),
            SequentialBandwidthTracker(band.outbound, peer.simExecutor)
        )
    }

    val nodeCount = 1000
    val nodePeerCount = 30
    val messageCount = 1
    val blockSize = 1024 * (1 shl 10)
    val blobSize = 1024 * (1 shl 10)
    val randomSeed = 2L

    @Test
    fun testCoupled() {
        val topic = Topic(BlocksTopic)
        val simConfig = GossipSimConfig(
            totalPeers = nodeCount,
            topics = listOf(topic),
            topology = RandomNPeers(nodePeerCount),
            gossipValidationDelay = 10.millis,
            bandwidthGenerator = {
                val band = peerBandwidths(it)
                bandwidthFactory(band, it)
            },
            startRandomSeed = randomSeed
        )

        val gossipParams = Eth2DefaultGossipParams
        val gossipScoreParams = Eth2DefaultScoreParams
        val gossipRouterCtor = { _: Int ->
            GossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
            }
        }

        val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor)
        println("Creating peers...")
        simNetwork.createAllPeers()
        println("Connecting peers...")
        simNetwork.connectAllPeers()

//        simNetwork.network.activeConnections.forEach { println("${it.dialer.name}  => ${it.listener.name}") }

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)

        for (i in 0 until messageCount) {
            println("Sending message $i")
            simulation.publishMessage(i, blockSize + blobSize, topic)
            simulation.forwardTime(1.minutes)
        }

        println("Gathering results...")
        val results = simulation.gatherMessageResults()

        val msgDelayStats = StatsFactory.DEFAULT.createStats("msgDelay").also {
            it += results.entries.flatMap { e ->
                e.value.map { it.receivedTime - e.key.sentTime }
            }
        }
        println("Delivery stats: $msgDelayStats")
        println("Network stats: " + simNetwork.network.networkStats)
    }

    @Test
    fun testDecoupled() {
        val blockTopic = Topic(BlocksTopic)
        val blobTopic = Topic("/eth2/00000000/beacon_blob/ssz_snappy")
        val simConfig = GossipSimConfig(
            totalPeers = nodeCount,
            topics = listOf(blockTopic, blobTopic),
            topology = RandomNPeers(nodePeerCount),
            gossipValidationDelay = 10.millis,
            bandwidthGenerator = {
                val band = peerBandwidths(it)
                bandwidthFactory(band, it)
            },
            startRandomSeed = randomSeed
        )

        val gossipParams = Eth2DefaultGossipParams
        val gossipScoreParams = Eth2DefaultScoreParams
        val gossipRouterCtor = { _: Int ->
            GossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
            }
        }

        val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor)
        println("Creating peers...")
        simNetwork.createAllPeers()
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)

        for (i in 0 until messageCount) {
            println("Sending message $i")
            simulation.publishMessage(i, blockSize, blockTopic)
            simulation.publishMessage(i, blobSize, blobTopic)
            simulation.forwardTime(1.minutes)
        }

        println("Gathering results...")
        val results = simulation.gatherMessageResults()

        val msgDelayStats = StatsFactory.DEFAULT.createStats("msgDelay").also {
            it += results.entries.flatMap { e ->
                e.value.map { it.receivedTime - e.key.sentTime }
            }
        }
        println("Delivery stats: $msgDelayStats")
        println("Network stats: " + simNetwork.network.networkStats)
    }

    @Test
    fun testMinimal() {
        testMinimalImpl(false)
        testMinimalImpl(true)
    }
    fun testMinimalImpl(decoupled: Boolean) {
//        val decoupled = false
        val blockTopic = Topic(BlocksTopic)
        val blobTopic = Topic("/eth2/00000000/beacon_blob/ssz_snappy")
        val simConfig = GossipSimConfig(
            totalPeers = 100,
            topics = listOf(blockTopic, blobTopic),
            topology = RandomNPeers(10),
//            topology = CustomTopology(
//                listOf(
//                    0 to 1,
//                    0 to 2,
//                    1 to 3,
//                    2 to 3
//                )
//            ),
            gossipValidationDelay = 10.millis,
            bandwidthGenerator = {
                val band = peerBandwidths(it)
                bandwidthFactory(band, it)
            },
            startRandomSeed = randomSeed
        )

        val gossipParams = Eth2DefaultGossipParams
        val gossipScoreParams = Eth2DefaultScoreParams
        val gossipRouterCtor = { _: Int ->
            GossipRouterBuilder().also {
                it.params = gossipParams
                it.scoreParams = gossipScoreParams
            }
        }

        val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor)
        println("Creating peers...")
        simNetwork.createAllPeers()
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)

        println("Sending message ")
        if (decoupled) {
            simulation.publishMessage(0, blockSize, blockTopic)
            simulation.publishMessage(0, blobSize, blobTopic)
        } else {
            simulation.publishMessage(0, blockSize + blobSize, blockTopic)
        }
        simulation.forwardTime(1.minutes)


        println("Gathering results...")
        val results = simulation.gatherMessageResults()

        val msgDelayStats = StatsFactory.DEFAULT.createStats("msgDelay").also {
            it += results.entries.flatMap { e ->
                e.value.map { it.receivedTime - e.key.sentTime }
            }
        }
        println("Delivery stats: $msgDelayStats")
        println("Network stats: " + simNetwork.network.networkStats)
    }
}