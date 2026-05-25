package io.libp2p.quicsim.scenario

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.program.SampleGossipNodeProgram
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object QuicScenarios {
    const val SLOW_START = "quic-slow-start"
    const val SAMPLE_GOSSIP_100 = "quic-sample-gossip-100"

    fun slowStart(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<DataChunkNodeProgramFactory> {
        val nodeCount = 2
        return QuicScenario(
            name = SLOW_START,
            network = QuicNetworkTopology.star(
                hostCount = nodeCount,
                latency = 100.milliseconds,
                bandwidthBytesPerSecond = 1_000_000L
            ),
            maxRunDuration = 100.seconds,
            createNodeProgramFactory = {
                DataChunkNodeProgramFactory(
                    nodeCount = nodeCount,
                    chunks = listOf(
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 1_000_000,
                            at = 10.seconds,
                            from = 0,
                            to = 1
                        ),
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 1_000_000,
                            at = 30.seconds,
                            from = 0,
                            to = 1
                        )
                    ),
                    eventSink = eventSink
                )
            }
        )
    }

    fun sampleGossip100(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<NodeProgramFactory> {
        val nodeCount = 100
        val publishersCount = 100
        val neighboursToConnect = 20
        val randomConnectionsByNode =
            createBidirectionalRandomTopology(nodeCount, neighboursToConnect, seed = 1234)

        return QuicScenario(
            name = SAMPLE_GOSSIP_100,
            network = QuicNetworkTopology.star(
                hostCount = nodeCount,
                latency = 100.milliseconds,
                bandwidthBytesPerSecond = 5_000_000L
            ),
            maxRunDuration = 10.minutes,
            createNodeProgramFactory = {
                object : NodeProgramFactory, QuicScenarioEventSource {
                    override fun createNode(id: SimNodeId): NodeProgram =
                        SampleGossipNodeProgram(
                            simNodeId = id,
                            connectToNodeIds = randomConnectionsByNode.getValue(id),
                            publishersCount = publishersCount,
                            params = GossipParams(),
                            randomSeed = id.toLong(),
                            messageSizeBytes = 180,
                            initialPublishDelay = 30.seconds,
                            eventSink = eventSink
                        )

                    override fun events(): List<QuicScenarioEvent> =
                        (eventSink as? QuicScenarioEventSource)?.events().orEmpty()
                }
            }
        )
    }

    fun byName(
        name: String,
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<NodeProgramFactory> =
        when (name) {
            SLOW_START -> slowStart(eventSink)
            SAMPLE_GOSSIP_100 -> sampleGossip100(eventSink)
            else -> throw IllegalArgumentException("Unknown QUIC scenario: $name")
        }

    private fun createBidirectionalRandomTopology(
        nodeCount: Int,
        neighboursToConnect: Int,
        seed: Int
    ): Map<SimNodeId, List<SimNodeId>> {
        require(neighboursToConnect in 0 until nodeCount) {
            "neighboursToConnect must be in [0, $nodeCount), got $neighboursToConnect"
        }
        require((nodeCount * neighboursToConnect) % 2 == 0) {
            "nodeCount * neighboursToConnect must be even for bidirectional topology"
        }

        val random = Random(seed)
        val permutation = (0 until nodeCount).shuffled(random)
        val adjacency = MutableList(nodeCount) { mutableSetOf<Int>() }

        val evenDegree = neighboursToConnect and 1.inv()
        val half = evenDegree / 2
        for (i in permutation.indices) {
            val a = permutation[i]
            for (step in 1..half) {
                val b = permutation[(i + step) % nodeCount]
                adjacency[a] += b
                adjacency[b] += a
            }
        }

        if ((neighboursToConnect and 1) == 1) {
            require(nodeCount % 2 == 0) { "Odd degree requires even nodeCount" }
            val halfNodes = nodeCount / 2
            for (i in 0 until halfNodes) {
                val a = permutation[i]
                val b = permutation[(i + halfNodes) % nodeCount]
                adjacency[a] += b
                adjacency[b] += a
            }
        }

        check(adjacency.all { it.size == neighboursToConnect }) {
            "Failed to generate bidirectional topology: nodeCount=$nodeCount degree=$neighboursToConnect"
        }

        return adjacency
            .mapIndexed { nodeId, peers -> nodeId to peers.toList().sorted() }
            .toMap()
    }
}
