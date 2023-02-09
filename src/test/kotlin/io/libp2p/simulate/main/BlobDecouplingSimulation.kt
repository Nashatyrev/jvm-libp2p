package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.security.logger
import io.libp2p.simulate.*
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.stats.Stats
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stream.randomLatencyDelayer
import io.libp2p.simulate.stream.simpleLatencyDelayer
import io.libp2p.simulate.topology.CustomTopology
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.util.chunked
import io.libp2p.simulate.util.countByRanges
import io.libp2p.simulate.util.toMap
import io.libp2p.tools.log
import io.libp2p.tools.millis
import io.libp2p.tools.minutes
import io.libp2p.tools.seconds
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

data class PeerBandwidthValue(
    val inbound: Bandwidth,
    val outbound: Bandwidth
)

class BlobDecouplingSimulation(
    val logger: (String) -> Unit = { log(it) },

    val messageValidationDelay: Duration = 10.millis,
    val latency: RandomDistribution = RandomDistribution.uniform(0.0, 50.0),

    val nodeCount: Int = 1000,
    val nodePeerCount: Int = 30,
    val messageCount: Int = 1,

    val blockSize: Int = 128 * 1024,
    val blobCount: Int = 4,
    val blobSize: Int = 128 * 1024,
    val randomSeed: Long = 2L,
    val rnd: Random = Random(randomSeed),

    val floodPublish: Boolean = true,

    val zeroPeerBand: Bandwidth = Bandwidth.mbitsPerSec(100),
    val otherPeerBands: Iterator<Bandwidth> = iterator {
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
        }
    }

) {

    val peerBandwidths: (GossipSimPeer) -> PeerBandwidthValue = { peer ->
        val inOutBand = if (peer.name == "0") {
            zeroPeerBand
        } else {
            otherPeerBands.next()
        }
        PeerBandwidthValue(inOutBand, inOutBand)
    }
    val bandwidthFactory: (PeerBandwidthValue, GossipSimPeer) -> PeerBandwidth = { band, peer ->
        PeerBandwidth(
            AnotherBetterBandwidthTracker(band.inbound, peer.simExecutor, peer.currentTime),
            AnotherBetterBandwidthTracker(band.outbound, peer.simExecutor, peer.currentTime)
        )
    }

    val blockTopic = Topic(BlocksTopic)
    val blobTopics = (0 until blobCount)
        .map {
            Topic("/eth2/00000000/beacon_blob_$it/ssz_snappy")
        }
    val simConfig = GossipSimConfig(
        totalPeers = nodeCount,
        topics = listOf(blockTopic) + blobTopics,
        topology = RandomNPeers(nodePeerCount),
        gossipValidationDelay = messageValidationDelay,
        bandwidthGenerator = {
            val band = peerBandwidths(it)
            bandwidthFactory(band, it)
        },
        latencyGenerator = { it.randomLatencyDelayer(latency.newValue(rnd)) },
        startRandomSeed = randomSeed
    )

    val gossipParams = Eth2DefaultGossipParams
        .copy(
//            heartbeatInterval = 1.minutes
            floodPublish = floodPublish
        )
    val gossipScoreParams = Eth2DefaultScoreParams
    val gossipRouterCtor = { _: Int ->
        GossipRouterBuilder().also {
            it.params = gossipParams
            it.scoreParams = gossipScoreParams
        }
    }

    val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor).also { simNetwork ->
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()

    }

    val simulation = run {
        logger("Creating simulation...")
        GossipSimulation(simConfig, simNetwork).also { simulation ->
            logger("Forwarding heartbeat time...")
            simulation.forwardTime(gossipParams.heartbeatInterval)
            logger("Cleaning warmup messages and network stats...")
            simulation.clearAllMessages()
            simulation.network.network.resetStats()
        }
    }

    fun printResults() {
        logger("Gathering results...")

        val messageDelayStats = gatherMessageDelayStats()

        logger("Results:")
        logger("Delivery stats: $messageDelayStats")
        logger("Network stats: " + simNetwork.network.networkStats)
    }

    fun gatherMessageDelaysByPeer(): Map<Int, Long> {
        val results = simulation.gatherMessageResults()

        data class MsgDelivery(
            val origMsg: SimMessage,
            val deliveredMsg: SimMessageDelivery
        )

        val flattenedDeliveries = results.entries.flatMap { (origMsg, deliveries) ->
            deliveries.map { MsgDelivery(origMsg, it) }
        }
        val deliveriesByTarget = flattenedDeliveries
            .groupBy { it.deliveredMsg.receivedPeer }
        val allMessagesDeliverTimes: Map<Int, Long> = deliveriesByTarget
            .mapValues { (_, deliver) ->
                if (deliver.size < results.size) Int.MAX_VALUE.toLong()
                else {
                    deliver.maxOf { it.deliveredMsg.receivedTime - it.origMsg.sentTime }
                }
            }
        return allMessagesDeliverTimes
    }

    fun gatherMessageDelayStats(): Stats {
        val delaysByPeer = gatherMessageDelaysByPeer()

        val msgDelayStats = StatsFactory.DEFAULT.createStats("msgDelay")
        msgDelayStats += delaysByPeer.values

        return msgDelayStats
    }

    fun allPublishedDelivered(msgCount: Int, sendingPeer: Int) =
        (simNetwork.peers - sendingPeer).values.all { peer ->
            peer.allMessages.size >= msgCount
        }


    @Test
    fun testCoupled() {
        for (i in 0 until messageCount) {
            logger("Sending message $i")
            simulation.publishMessage(i, blockSize + blobSize * blobCount, blockTopic)
            for (j in 0..59) {
//                logger("Forwarding time $j...")
                simulation.forwardTime(1.seconds)
                if (allPublishedDelivered(1, i))
                    break
            }
        }

        printResults()
    }

    @Test
    fun testOnlyBlockDecoupled() {

        for (i in 0 until messageCount) {
            logger("Sending message $i")
            simulation.publishMessage(i, blockSize, blockTopic)
            simulation.publishMessage(i, blobSize * blobCount, blobTopics[0])
            for (j in 0..59) {
//                logger("Forwarding time $j ...")
                simulation.forwardTime(1.seconds)
                if (allPublishedDelivered(2, i))
                    break
            }
        }

        printResults()
    }

    @Test
    fun testAllDecoupled() {

        for (i in 0 until messageCount) {
            logger("Sending message $i")
            simulation.publishMessage(i, blockSize, blockTopic)
            (0 until blobCount).forEach {
                simulation.publishMessage(i, blobSize, blobTopics[it])
            }
            for (j in 0..59) {
//                logger("Forwarding time $j ...")
                simulation.forwardTime(1.seconds)
                if (allPublishedDelivered(blobCount + 1, i))
                    break
            }
        }

        printResults()
    }
}

fun main() {
//    val bandwidths = bandwidthDistributions

    val delayRanges = (0L until 17_000L).chunked(20)

    val bandwidths = bandwidthDistributions.entries.toList()
        .let {
            listOf(it[0], it[2])
        }.toMap()

    bandwidths.forEach { (name, band) ->
        fun getResults(sim: BlobDecouplingSimulation): String {
            val messageDelayStats = sim.gatherMessageDelayStats().getStatisticalSummary()
            val networkStats = sim.simNetwork.network.networkStats
            return "${messageDelayStats.min.toLong()}\t" +
                    "${messageDelayStats.mean.toLong()}\t" +
                    "${messageDelayStats.max.toLong()}\t" +
                    "${networkStats.msgCount}\t" +
                    "${networkStats.traffic}"
        }

        fun getRangedDelays(sim: BlobDecouplingSimulation): String {
            val delays = sim.gatherMessageDelaysByPeer()
            val countByRanges = delays.values.countByRanges(delayRanges)
            return delayRanges
                .zip(countByRanges)
                .map { (range, count) ->
                    "${range.first}\t$count"
                }
                .joinToString("\n")
        }

        fun createSimulation() =
            BlobDecouplingSimulation(
//                logger = {},
                nodeCount = 1000,
                otherPeerBands = band,
                floodPublish = false,
//                randomSeed = 2
            )

        createSimulation().also {
            it.testCoupled()
            println("$name\tCoupled\t${getResults(it)}\n" /*+ getRangedDelays(it)*/)
        }
//        createSimulation().also {
//            it.testOnlyBlockDecoupled()
//            println("$name\tSemi-decoupled\t${getResults(it)}")
//        }
        createSimulation().also {
            it.testAllDecoupled()
            println("$name\tDecoupled\t${getResults(it)}\n" /*+ getRangedDelays(it)*/)
        }
    }
}

val bandwidthDistributions = mapOf(
    "100% 100Mbps" to iterator {
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
        }
    },
    "10% 10Mbps, 80% 100Mbps, 10% 190Mbps" to iterator {
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(10))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(190))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(100))
        }
    },
    "20% 10Mbps, 60% 100Mbps, 20% 190Mbps" to iterator {
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(10))
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(190))
            yield(Bandwidth.mbitsPerSec(100))
        }
    },
    "33% 10Mbps, 33% 100Mbps, 33% 190Mbps" to iterator {
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(10))
            yield(Bandwidth.mbitsPerSec(190))
        }
    }
)