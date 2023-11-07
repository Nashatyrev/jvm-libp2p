package io.libp2p.simulate.pubsub.erasure

import io.libp2p.core.pubsub.Topic
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.DelayDetails
import io.libp2p.simulate.RandomDistribution
import io.libp2p.simulate.SimChannelMessageVisitor
import io.libp2p.simulate.SimConnection
import io.libp2p.simulate.TopologyGraph
import io.libp2p.simulate.delay.latency.LatencyDistribution
import io.libp2p.simulate.pubsub.createGenericPubsubMessageSizes
import io.libp2p.simulate.pubsub.erasure.router.SimErasureCoder
import io.libp2p.simulate.pubsub.erasure.router.SimErasureRouterBuilder
import io.libp2p.simulate.pubsub.trickyMessageBodyGenerator
import io.libp2p.simulate.topology.CustomTopologyGraph
import io.libp2p.simulate.topology.RandomNPeers
import io.libp2p.simulate.topology.asFixedTopology
import io.libp2p.tools.log
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class SanityErasureSimulationTest {

    val logger: (String) -> Unit = { log(it) }
    val nodeCount: Int = 5
    val testTopic: Topic = Topic("Topic-1")
    val messageSize: Int = 4 * 128 * 1024

    val messageSizes = trickyMessageBodyGenerator.createGenericPubsubMessageSizes()

    val sampleSendStrategy: (SampledMessage) -> SampleSendStrategy = { SampleSendStrategy.cWndStrategy(32) }
    val ackSendStrategy: (SampledMessage) -> AckSendStrategy = AckSendStrategy.Companion::allInboundAndWhenComplete
    val simErasureCoder = SimErasureCoder(
        sampleSize = 8 * 1024,
        extensionFactor = 2,
        sampleExtraSize = 1024,
        headerSize = 512,
        messageBodyGenerator = messageSizes.messageBodyGenerator
    )

    val simConfig: ErasureSimConfig = ErasureSimConfig(
        peerConfigs = ErasureSimPeerConfigGenerator(
            topics = listOf(testTopic),
            bandwidths = RandomDistribution.const(Bandwidth.mbitsPerSec(10)),
            messageValidationDelays = RandomDistribution.const(0.milliseconds)
        ).generate(0, nodeCount),
//        topology = RandomNPeers(10),
        topology = CustomTopologyGraph(
            listOf(
                TopologyGraph.Edge(0,1),
                TopologyGraph.Edge(0,2),
                TopologyGraph.Edge(0,4),
                TopologyGraph.Edge(1,2),
                TopologyGraph.Edge(2,3),
                TopologyGraph.Edge(2,4),
                TopologyGraph.Edge(3,4),
            )
        ).asFixedTopology(),
        latency = LatencyDistribution.createUniformConst(5.milliseconds, 50.milliseconds),
        sampleSendStrategy = sampleSendStrategy,
        ackSendStrategy = ackSendStrategy,
        simErasureCoder = simErasureCoder
    )

    private fun addConnectionLogging(conn: SimConnection) {
        conn.streams
            .withIndex()
            .forEach { (streamIndex, stream) ->
                listOf(stream.acceptorChannel, stream.initiatorChannel)
                    .forEach { channel ->
                        channel.msgVisitors += object : SimChannelMessageVisitor {
                            override fun onOutbound(message: Any) {
                                println(" <====   ($channel) $message")
                            }

                            override fun onInbound(message: Any, delayDetails: DelayDetails) {
                                println("   ====> ($channel) $message")
                            }

                        }
                    }
            }
    }

    @Test
    fun sanityTest() {
        val simNetwork = ErasureSimNetwork(simConfig) {
            SimErasureRouterBuilder()
        }
        logger("Creating peers...")
        simNetwork.createAllPeers()
        logger("Connecting peers...")
        simNetwork.connectAllPeers()
        logger("Peers connected.")

//        addConnectionLogging(simNetwork.network.activeConnections[0])

        logger("Creating simulation...")
        val simulation = ErasureSimulation(simConfig, simNetwork)

        logger("Sending message at time ${simulation.network.timeController.time}...")
        simulation.publishMessage(0, messageSize, testTopic)

        simulation.forwardTimeUntilAllPubDelivered()
        logger("All messages delivered at time ${simulation.network.timeController.time}")
        simulation.forwardTimeUntilNoPendingMessages()
        logger("No more pending messages at time ${simulation.network.timeController.time}")

        val deliveredApiMessages = simulation.apiMessageDeliveries
        logger("API messages delivered: ${deliveredApiMessages.size}")
        assertThat(deliveredApiMessages.size).isEqualTo(nodeCount - 1)

        val maxDeliverTime = deliveredApiMessages.maxOf { it.receiveTime - it.message.sentTime }
        logger("API messages deliver max time: $maxDeliverTime")
        assertThat(maxDeliverTime).isLessThan(2000)

        val messagesRes = simulation.messageCollector.gatherResult()
        logger("RPC messages: ${messagesRes.getTotalMessageCount()}")
        logger("Total traffic: ${messagesRes.getTotalTraffic()}")
        val idealTraffic = messageSize * (nodeCount - 1)
        val excessFactor = messagesRes.getTotalTraffic().toDouble() / idealTraffic
        logger("Traffic excess factor: $excessFactor")
        assertThat(excessFactor).isGreaterThan(1.0)

        logger("ErasureHeader count: " + messagesRes.erasureHeaderMessages.size)
        assertThat(messagesRes.erasureHeaderMessages.size).isGreaterThanOrEqualTo(nodeCount)
        logger("ErasureSample count: " + messagesRes.erasureSampleMessages.size)
        assertThat(messagesRes.erasureSampleMessages.size).isGreaterThanOrEqualTo(nodeCount)
        logger("ErasureAck count: " + messagesRes.erasureAckMessages.size)
        assertThat(messagesRes.erasureAckMessages.size).isGreaterThanOrEqualTo(nodeCount)
    }
}