package io.libp2p.simulate.main

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.*
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.stats.StatsFactory
import io.libp2p.simulate.stream.randomLatencyDelayer
import io.libp2p.simulate.stream.simpleLatencyDelayer
import io.libp2p.simulate.topology.CustomTopology
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.tools.log
import io.libp2p.tools.millis
import io.libp2p.tools.minutes
import io.libp2p.tools.seconds
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

class BlobDecouplingSimulation {

    data class PeerBandwidthValue(
        val inbound: Bandwidth,
        val outbound: Bandwidth
    )

    val zeroPeerBand = Bandwidth.mbitsPerSec(100)
    val otherPeerBands = iterator {
        while (true) {
            yield(Bandwidth.mbitsPerSec(100))
            yield(Bandwidth.mbitsPerSec(10))
            yield(Bandwidth.mbitsPerSec(190))

//            yield(Bandwidth.mbitsPerSec(100))
//            yield(Bandwidth.mbitsPerSec(100))
//            yield(Bandwidth.mbitsPerSec(10))
//            yield(Bandwidth.mbitsPerSec(100))
//            yield(Bandwidth.mbitsPerSec(100))
//            yield(Bandwidth.mbitsPerSec(100))
//            yield(Bandwidth.mbitsPerSec(100))
//            yield(Bandwidth.mbitsPerSec(190))
//            yield(Bandwidth.mbitsPerSec(100))
//            yield(Bandwidth.mbitsPerSec(100))
        }
    }

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
    val messageValidationDelay = 10.millis
    val latency = RandomDistribution.uniform(0.0, 50.0)

    val nodeCount = 1000
    val nodePeerCount = 30
    val messageCount = 1

    val blockSize = 128 * 1024
    val blobCount = 4
    val blobSize = 128 * 1024
    val randomSeed = 2L
    val rnd = Random(randomSeed)

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
            floodPublish = false
        )
    val gossipScoreParams = Eth2DefaultScoreParams
    val gossipRouterCtor = { _: Int ->
        GossipRouterBuilder().also {
            it.params = gossipParams
            it.scoreParams = gossipScoreParams
        }
    }

    val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor).also { simNetwork ->
        log("Creating peers...")
        simNetwork.createAllPeers()
        log("Connecting peers...")
        simNetwork.connectAllPeers()

    }

    val simulation = run {
        log("Creating simulation...")
        GossipSimulation(simConfig, simNetwork).also { simulation ->
            log("Forwarding heartbeat time...")
            simulation.forwardTime(gossipParams.heartbeatInterval)
            log("Cleaning warmup messages and network stats...")
            simulation.clearAllMessages()
            simulation.network.network.resetStats()
        }
    }

    fun printResults() {
        log("Gathering results...")
        val results = simulation.gatherMessageResults()

        val msgDelayStats = StatsFactory.DEFAULT.createStats("msgDelay")

        data class MsgDelivery(
            val origMsg: SimMessage,
            val deliveredMsg: SimMessageDelivery
        )

        val flattenedDeliveries = results.entries.flatMap { (origMsg, deliveries) ->
            deliveries.map { MsgDelivery(origMsg, it) }
        }
        val deliveriesByTarget = flattenedDeliveries
            .groupBy { it.deliveredMsg.receivedPeer }
        val allMessagesDeliverTimes = deliveriesByTarget
            .mapValues { (_, deliver) ->
                    if (deliver.size < results.size) Int.MAX_VALUE.toLong()
                    else {
                        deliver.maxOf { it.deliveredMsg.receivedTime - it.origMsg.sentTime }
                    }
                }

        msgDelayStats += allMessagesDeliverTimes.values

        log("Results:")
        println("Delivery stats: $msgDelayStats")
        println("Network stats: " + simNetwork.network.networkStats)
    }

    fun allPublishedDelivered(msgCount: Int, sendingPeer: Int) =
        (simNetwork.peers - sendingPeer).values.all { peer ->
            peer.allMessages.size >= msgCount
        }


    @Test
    fun testCoupled() {
        for (i in 0 until messageCount) {
            log("Sending message $i")
            simulation.publishMessage(i, blockSize + blobSize * blobCount, blockTopic)
            for (j in 0..59) {
                log("Forwarding time $j...")
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
            log("Sending message $i")
            simulation.publishMessage(i, blockSize, blockTopic)
            simulation.publishMessage(i, blobSize * blobCount, blobTopics[0])
            for (j in 0..59) {
                log("Forwarding time $j ...")
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
            log("Sending message $i")
            simulation.publishMessage(i, blockSize, blockTopic)
            (0 until blobCount).forEach {
                simulation.publishMessage(i, blobSize, blobTopics[it])
            }
            for (j in 0..59) {
                log("Forwarding time $j ...")
                simulation.forwardTime(1.seconds)
                if (allPublishedDelivered(blobCount + 1, i))
                    break
            }
        }

        printResults()
    }
}