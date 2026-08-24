package io.libp2p.quicsim.runner

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.NEVER_FLOOD_PUBLISH
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.program.SampleGossipNodeProgram
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.WORLD_DESCRIPTOR_1
import io.libp2p.quicsim.scenario.RegionalNetworkTopologyBuilder
import io.libp2p.quicsim.scenario.RecordingQuicScenarioEventSink
import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.scenario.QuicScenarioEventSource
import io.libp2p.quicsim.scenario.addRandomScenarioHosts
import io.libp2p.quicsim.sim.SimNodeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class RegionalGossipTopologyTest {
    @Test
    @Timeout(120)
    fun `one node disseminates a 512KiB gossip message to every other regional node`() {
        val eventSink = RecordingQuicScenarioEventSink()
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val connectToNodeIds = randomOutboundConnections(
            nodeCount = NODE_COUNT,
            peersPerNode = PEERS_PER_NODE,
            seed = overlaySeed
        )
        val topology = RegionalNetworkTopologyBuilder(WORLD_DESCRIPTOR_1)
            .addRandomScenarioHosts(hostId = IPManager.Default::getIP)
            .build()
        val previousLogging = System.getProperty(SAMPLE_GOSSIP_LOG_PROPERTY)
        System.setProperty(SAMPLE_GOSSIP_LOG_PROPERTY, "false")

        try {
            val runner = SimulatedRunner(
                nodeFactory = object : NodeProgramFactory {
                    override fun createNode(id: SimNodeId): NodeProgram =
                        SampleGossipNodeProgram(
                            simNodeId = id,
                            connectToNodeIds = connectToNodeIds.getValue(id),
                            publishersCount = PUBLISHER_COUNT,
                            params = gossipParams(),
                            randomSeed = gossipSeedBase + id,
                            messageSizeBytes = MESSAGE_SIZE_BYTES,
                            initialPublishDelay = INITIAL_PUBLISH_DELAY,
                            eventSink = eventSink
                        ).also { nodePrograms += it }
                },
                udpNetwork = topology.toUdpSimNetwork(),
                maxSimulatedRunDuration = MAX_RUN_DURATION,
                latencyWindowParallelism = LATENCY_WINDOW_PARALLELISM
            )

            try {
                runner.run()
            } catch (t: Throwable) {
                println(nodePrograms.withIndex().joinToString("\n") { (index, program) ->
                    "node-$index ${program.debugState()}"
                })
                throw t
            }
        } finally {
            if (previousLogging == null) {
                System.clearProperty(SAMPLE_GOSSIP_LOG_PROPERTY)
            } else {
                System.setProperty(SAMPLE_GOSSIP_LOG_PROPERTY, previousLogging)
            }
        }

        assertTrue(
            nodePrograms.all { it.completeFuture.isDone },
            "Expected every sample gossip node program to complete"
        )

        val events = (eventSink as QuicScenarioEventSource).events()
        val publications = messagePublications(events)
        val receipts = messageReceipts(events)
            .filter { it.publishingNodeId == PUBLISHER_NODE_ID }
        val expectedRecipients = (0 until NODE_COUNT)
            .filter { it != PUBLISHER_NODE_ID }
            .toSet()

        assertEquals(listOf(PUBLISHER_NODE_ID), publications.map { it.publishingNodeId })
        assertEquals(expectedRecipients, receipts.map { it.receivingNodeId }.toSet())
        assertEquals(NODE_COUNT - 1, receipts.size)

        val publishedAt = publications.single().publishedAt
        val disseminationLatencies = receipts.map { it.receivedAt - publishedAt }
        val p95 = percentile(disseminationLatencies, 0.95)
        println(
            "Regional gossip 512KiB dissemination: " +
                "receipts=${receipts.size} p95=${p95.inWholeMilliseconds}ms"
        )
    }

    private fun gossipParams(): GossipParams =
        GossipParams(
            D = 3,
            DLow = 2,
            DHigh = 4,
            DOut = 1,
            DLazy = 0,
            gossipFactor = 0.0,
            heartbeatInterval = 700.milliseconds.toJavaDuration(),
            gossipHistoryLength = 5,
            gossipSize = 3,
            floodPublishMaxMessageSizeThreshold = NEVER_FLOOD_PUBLISH,
            maxGossipMessageSize = MESSAGE_SIZE_BYTES * 2,
            iDontWantMinMessageSizeThreshold = Int.MAX_VALUE
        )

    private fun randomOutboundConnections(
        nodeCount: Int,
        peersPerNode: Int,
        seed: Int
    ): Map<SimNodeId, List<SimNodeId>> {
        require(peersPerNode in 0 until nodeCount) {
            "peersPerNode must be in [0, $nodeCount), got $peersPerNode"
        }

        val random = Random(seed)
        return (0 until nodeCount).associateWith { nodeId ->
            (0 until nodeCount)
                .filter { it != nodeId }
                .shuffled(random)
                .take(peersPerNode)
        }
    }

    private fun percentile(values: List<Duration>, percentile: Double): Duration {
        require(values.isNotEmpty()) { "values must not be empty" }
        require(percentile in 0.0..1.0) { "percentile must be in [0, 1]" }
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * percentile).toInt()
        return sorted[index]
    }

    private fun messageReceipts(events: List<QuicScenarioEvent>): List<MessageReceipt> =
        events.filterIsInstance<QuicScenarioEvent.GossipMessageReceived>()
            .map {
                MessageReceipt(
                    receivedAt = it.at,
                    receivingNodeId = it.nodeId,
                    publishingNodeId = it.publisherNodeId
                )
            }
            .sortedWith(compareBy({ it.receivedAt }, { it.receivingNodeId }, { it.publishingNodeId }))

    private fun messagePublications(events: List<QuicScenarioEvent>): List<MessagePublication> =
        events.filterIsInstance<QuicScenarioEvent.GossipMessagePublished>()
            .map {
                MessagePublication(
                    publishedAt = it.at,
                    publishingNodeId = it.nodeId
                )
            }
            .sortedWith(compareBy({ it.publishedAt }, { it.publishingNodeId }))

    private data class MessageReceipt(
        val receivedAt: Duration,
        val receivingNodeId: SimNodeId,
        val publishingNodeId: SimNodeId
    )

    private data class MessagePublication(
        val publishedAt: Duration,
        val publishingNodeId: SimNodeId
    )

    private companion object {
        const val NODE_COUNT = 65
        const val PEERS_PER_NODE = 50
        const val PUBLISHER_COUNT = 1
        const val PUBLISHER_NODE_ID = 0
        const val MESSAGE_SIZE_BYTES = 512 * 1024
        const val LATENCY_WINDOW_PARALLELISM = 8
        const val SAMPLE_GOSSIP_LOG_PROPERTY = "quicsim.sampleGossip.log"
        val INITIAL_PUBLISH_DELAY = 10.seconds
        val MAX_RUN_DURATION = 2.minutes
        val overlaySeed = Integer.getInteger("quicsim.regionalGossip.overlaySeed", 7_123)
        val gossipSeedBase = java.lang.Long.getLong("quicsim.regionalGossip.gossipSeedBase", 19_000L)
    }
}
