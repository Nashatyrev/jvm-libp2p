package io.libp2p.simulate.test

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.gossip.builders.GossipRouterBuilder
import io.libp2p.simulate.*
import io.libp2p.simulate.BetterBandwidthTracker.Companion.split
import io.libp2p.simulate.gossip.*
import io.libp2p.simulate.topology.AllToAllTopology
import io.libp2p.tools.millis
import io.libp2p.tools.seconds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class BandwidthTest {

    val topic = Topic("aaa")
    val simConfig = GossipSimConfig(
        totalPeers = 2,
        topics = listOf(topic),
        topology = AllToAllTopology(),
        gossipValidationDelay = 0.millis
    )

    val gossipParams = Eth2DefaultGossipParams
    val gossipScoreParams = Eth2DefaultScoreParams
    val gossipRouterCtor = { _: Int ->
        GossipRouterBuilder().also {
            it.params = gossipParams
            it.scoreParams = gossipScoreParams
//                it.serialize = true
        }
    }

    val simPeerModifier = { num: Int, peer: GossipSimPeer ->
//            peer.pubsubLogs = { true }
    }

    val simNetwork = GossipSimNetwork(simConfig, gossipRouterCtor, simPeerModifier).also {
        println("Creating peers...")
        it.createAllPeers()
    }
    val peer0 = simNetwork.peers[0]!!
    val peer1 = simNetwork.peers[1]!!

    @Test
    fun testInboundLarger() {
        peer0.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(200000), peer1.simExecutor)
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(1.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        val messageResults = simulation.gatherMessageResults()
        val (origMsg, res) = messageResults.entries.first()
        run {
            assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(2100, Offset.offset(100))
            println(res)
        }
    }

    @Test
    fun testInboundLargerWithLatency() {
        peer0.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(200000), peer1.simExecutor)
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        simNetwork.network.activeConnections.forEach {
            it.connectionLatency = TimeDelayer(peer0.simExecutor) { 1000.milliseconds }
        }

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(1.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        val messageResults = simulation.gatherMessageResults()
        val (origMsg, res) = messageResults.entries.first()
        run {
            println(res)
            assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(3100, Offset.offset(100))
        }
    }

    @Test
    fun testInboundSmaller() {
        peer0.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(50000), peer1.simExecutor)
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(1.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        val messageResults = simulation.gatherMessageResults()
        val (origMsg, res) = messageResults.entries.first()
        run {
            assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(4100, Offset.offset(100))
            println(res)
        }
    }

    @Test
    fun testInboundSmallerWithLatency() {
        peer0.outboundBandwidth = SimpleBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = SimpleBandwidthTracker(Bandwidth(50000), peer1.simExecutor)
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        simNetwork.network.activeConnections.forEach {
            it.connectionLatency = TimeDelayer(peer0.simExecutor) { 1000.milliseconds }
        }

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(1.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        val messageResults = simulation.gatherMessageResults()
        val (origMsg, res) = messageResults.entries.first()
        run {
            assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(5100, Offset.offset(100))
            println(res)
        }
    }

    @Test
    fun testSequentialBandwidth() {
        peer0.outboundBandwidth =
            SequentialBandwidthTracker(Bandwidth(100000), peer0.simExecutor)
        peer1.inboundBandwidth = BandwidthDelayer.UNLIM_BANDWIDTH
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(5.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.publishMessage(0, 100000, topic)
        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        run {
            val messageResults = simulation.gatherMessageResults()
            val resList = messageResults.entries.toList()
            assertThat(resList).hasSize(3)
            run {
                val (origMsg, res) = resList[0]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(2100, Offset.offset(100))
            }
            run {
                val (origMsg, res) = resList[1]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(3100, Offset.offset(100))
            }
            run {
                val (origMsg, res) = resList[2]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(5100, Offset.offset(100))
            }
        }

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(500.millis)
        simulation.publishMessage(0, 100000, topic)
        simulation.forwardTime(500.millis)
        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        run {
            val messageResults = simulation.gatherMessageResults()
            val resList = messageResults.entries
                .drop(3)
                .toList()
            assertThat(resList).hasSize(3)
            run {
                val (origMsg, res) = resList[0]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(2100, Offset.offset(100))
            }
            run {
                val (origMsg, res) = resList[1]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(2600, Offset.offset(100))
            }
            run {
                val (origMsg, res) = resList[2]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(4100, Offset.offset(100))
            }
        }
    }

    @Test
    fun testBetterBandwidth() {
        peer0.outboundBandwidth =
            BetterBandwidthTracker(Bandwidth(100000), peer0.simExecutor, peer0.currentTime)
        peer1.inboundBandwidth = BandwidthDelayer.UNLIM_BANDWIDTH
        println("Connecting peers...")
        simNetwork.connectAllPeers()

        println("Creating simulation...")
        val simulation = GossipSimulation(simConfig, simNetwork)
        simulation.forwardTime(5.seconds)

        simulation.publishMessage(0, 200000, topic)
        simulation.publishMessage(0, 100000, topic)
        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        run {
            val messageResults = simulation.gatherMessageResults()
            val resList = messageResults.entries.toList()
            assertThat(resList).hasSize(3)
            run {
                val (origMsg, res) = resList[0]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(5300, Offset.offset(50))
            }
            run {
                val (origMsg, res) = resList[1]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(3100, Offset.offset(50))
            }
            run {
                val (origMsg, res) = resList[2]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(5300, Offset.offset(50))
            }
        }

        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(500.millis)
        simulation.publishMessage(0, 100000, topic)
        simulation.forwardTime(500.millis)
        simulation.publishMessage(0, 200000, topic)
        simulation.forwardTime(10.seconds)

        run {
            val messageResults = simulation.gatherMessageResults()
            val resList = messageResults.entries
                .drop(3)
                .toList()
            assertThat(resList).hasSize(3)
            run {
                val (origMsg, res) = resList[0]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(5000, Offset.offset(100))
            }
            run {
                val (origMsg, res) = resList[1]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(3000, Offset.offset(100))
            }
            run {
                val (origMsg, res) = resList[2]
                assertThat(res[0].receivedTime - origMsg.sentTime).isCloseTo(4800, Offset.offset(100))
            }
        }
    }

    @Test
    fun testRangeSplit() {
        val chunks = (1000L..1010L).split(3)
        assertThat(chunks).hasSize(3)
        assertThat(chunks[0]).isEqualTo(1000L..1002L)
        assertThat(chunks[1]).isEqualTo(1003L..1006L)
        assertThat(chunks[2]).isEqualTo(1007L..1010L)
    }

    @Test
    fun testCalcDeliverTimes1() {
        val bandwidth = Bandwidth(1000)
        val t1 = BetterBandwidthTracker.calcDeliverTimes(
            bandwidth, listOf(
                BetterBandwidthTracker.Message(1000, 200_000),
                BetterBandwidthTracker.Message(1000, 200_000)
            ),
            10
        )
        assertThat(t1[0]).isCloseTo(202_000, Offset.offset(100))
        assertThat(t1[1]).isCloseTo(202_000, Offset.offset(100))
    }

    @Test
    fun testCalcDeliverTimes2() {
        val bandwidth = Bandwidth(1000)
        val t1 = BetterBandwidthTracker.calcDeliverTimes(
            bandwidth, listOf(
                BetterBandwidthTracker.Message(1000, 200_000),
                BetterBandwidthTracker.Message(1000, 200_800)
            ),
            10
        )
        assertThat(t1[0]).isCloseTo(201_200, Offset.offset(100))
        assertThat(t1[1]).isCloseTo(202_000, Offset.offset(100))
    }

    @Test
    fun testCalcDeliverTimes3() {
        val bandwidth = Bandwidth(1000)
        val t1 = BetterBandwidthTracker.calcDeliverTimes(
            bandwidth, listOf(
                BetterBandwidthTracker.Message(1000, 200_000),
                BetterBandwidthTracker.Message(50, 200_850)
            ),
            10
        )
        assertThat(t1[0]).isCloseTo(201_050, Offset.offset(50))
        assertThat(t1[1]).isCloseTo(200_950, Offset.offset(50))
    }

    @Test
    fun testCalcDeliverTimes4() {
        val bandwidth = Bandwidth(1000)
        val t1 = BetterBandwidthTracker.calcDeliverTimes(
            bandwidth, listOf(
                BetterBandwidthTracker.Message(1000, 200_000),
                BetterBandwidthTracker.Message(1, 200_100)
            ),
            10
        )
        assertThat(t1[0]).isCloseTo(201_050, Offset.offset(50))
        assertThat(t1[1]).isCloseTo(200_100, Offset.offset(10))
    }

    @Test
    fun testCalcDeliverTimes5() {
        val bandwidth = Bandwidth(1_000_000)
        val t1 = BetterBandwidthTracker.calcDeliverTimes(
            bandwidth, listOf(
                BetterBandwidthTracker.Message(1, 200_000),
                BetterBandwidthTracker.Message(1, 200_000)
            ),
            10
        )
        assertThat(t1[0]).isCloseTo(200_000, Offset.offset(5))
        assertThat(t1[1]).isCloseTo(200_000, Offset.offset(5))
    }

    @Test
    fun testCalcDeliverTimes6() {
        val bandwidth = Bandwidth(1000)
        val t1 = BetterBandwidthTracker.calcDeliverTimes(
            bandwidth, listOf(
                BetterBandwidthTracker.Message(1000, 200_000),
                BetterBandwidthTracker.Message(1000, 200_800),
                BetterBandwidthTracker.Message(20, 200_850)
            ),
            10
        )
        assertThat(t1[0]).isCloseTo(201_200, Offset.offset(100))
        assertThat(t1[1]).isCloseTo(202_000, Offset.offset(100))
    }

    @Test
    fun testCalcDeliverTimes7() {
        val bandwidth = Bandwidth(1000)
        val t1 = BetterBandwidthTracker.calcDeliverTimes(
            bandwidth, listOf(
                BetterBandwidthTracker.Message(1000, 200_000),
                BetterBandwidthTracker.Message(1000, 200_200),
                BetterBandwidthTracker.Message(20, 202_100)
            ),
            10
        )
        assertThat(t1[0]).isCloseTo(201_200, Offset.offset(100))
        assertThat(t1[1]).isCloseTo(202_000, Offset.offset(100))
    }
}