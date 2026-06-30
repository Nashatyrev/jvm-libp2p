package io.libp2p.quicsim.scenario

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.program.SampleGossipNodeProgram
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object QuicScenarios {
    const val SLOW_START = "quic-slow-start"
    const val SINGLE_TRANSFER_8MB = "quic-single-transfer-8mb"
    const val SINGLE_TRANSFER_8MB_HALF_RECEIVER_BW = "quic-single-transfer-8mb-half-receiver-bw"
    const val INBOUND_CONGESTION = "quic-inbound-congestion"
    const val SAMPLE_GOSSIP_100 = "quic-sample-gossip-100"
    const val SAMPLE_GOSSIP_20_SYNC_PUBLISH = "quic-sample-gossip-20-sync-publish"
    const val SAMPLE_GOSSIP_100_128K_10MS_5_PUBLISHERS = "quic-sample-gossip-100-128k-10ms-5pub"

    fun slowStart(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<DataChunkNodeProgramFactory> {
        val nodeCount = 2
        return QuicScenario(
            name = SLOW_START,
            network = QuicNetworkTopology.star(
                hostCount = nodeCount,
                latency = 100.milliseconds,
                bandwidthBytesPerSecond = 1_000_000L,
                maxQueueWaitTime = Duration.INFINITE
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

    fun singleTransfer8Mb(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<DataChunkNodeProgramFactory> {
        val nodeCount = 2
        return QuicScenario(
            name = SINGLE_TRANSFER_8MB,
            network = QuicNetworkTopology.star(
                hostCount = nodeCount,
                latency = 100.milliseconds,
                bandwidthBytesPerSecond = 2_000_000L,
                maxQueueWaitTime = Duration.INFINITE
            ),
            maxRunDuration = 100.seconds,
            createNodeProgramFactory = {
                DataChunkNodeProgramFactory(
                    nodeCount = nodeCount,
                    chunks = listOf(
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 8_000_000,
                            at = 10.seconds,
                            from = 0,
                            to = 1
                        )
                    ),
                    eventSink = eventSink
                )
            }
        )
    }

    fun singleTransfer8MbHalfReceiverBandwidth(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<DataChunkNodeProgramFactory> {
        val nodeCount = 2
        val routerId = "router-0"
        return QuicScenario(
            name = SINGLE_TRANSFER_8MB_HALF_RECEIVER_BW,
            network = QuicNetworkTopology(
                hosts = (0 until nodeCount).map { QuicNetworkHost("node-$it") },
                routers = listOf(QuicNetworkRouter(routerId)),
                links = listOf(
                    QuicNetworkLink(
                        from = "node-0",
                        to = routerId,
                        latency = 100.milliseconds,
                        bandwidthBytesPerSecond = 2_000_000L,
                        maxQueueWaitTime = Duration.INFINITE
                    ),
                    QuicNetworkLink(
                        from = routerId,
                        to = "node-0",
                        latency = 100.milliseconds,
                        bandwidthBytesPerSecond = 2_000_000L,
                        maxQueueWaitTime = Duration.INFINITE
                    ),
                    QuicNetworkLink(
                        from = "node-1",
                        to = routerId,
                        latency = 100.milliseconds,
                        bandwidthBytesPerSecond = 1_000_000L,
                        maxQueueWaitTime = Duration.INFINITE
                    ),
                    QuicNetworkLink(
                        from = routerId,
                        to = "node-1",
                        latency = 100.milliseconds,
                        bandwidthBytesPerSecond = 1_000_000L,
                        maxQueueWaitTime = Duration.INFINITE
                    )
                )
            ),
            maxRunDuration = 100.seconds,
            createNodeProgramFactory = {
                DataChunkNodeProgramFactory(
                    nodeCount = nodeCount,
                    chunks = listOf(
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 8_000_000,
                            at = 10.seconds,
                            from = 0,
                            to = 1
                        )
                    ),
                    eventSink = eventSink
                )
            }
        )
    }

    fun inboundCongestion(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<DataChunkNodeProgramFactory> {
        val nodeCount = 4
        return QuicScenario(
            name = INBOUND_CONGESTION,
            network = QuicNetworkTopology.star(
                hostCount = nodeCount,
                latency = 30.milliseconds,
                bandwidthBytesPerSecond = 1_000_000L,
                maxQueueWaitTime = Duration.INFINITE
            ),
            maxRunDuration = 100.seconds,
            createNodeProgramFactory = {
                DataChunkNodeProgramFactory(
                    nodeCount = nodeCount,
                    chunks = listOf(
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 1_000_000,
                            at = 10.seconds,
                            from = 1,
                            to = 0
                        ),
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 1_000_000,
                            at = 10.seconds + 300.milliseconds,
                            from = 2,
                            to = 0
                        ),
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 1_000_000,
                            at = 11.seconds,
                            from = 3,
                            to = 0
                        ),
                    ),
                    eventSink = eventSink
                )
            }
        )
    }

    fun sampleGossip100(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink(),
        topologySeed: Int = 1234,
        gossipSeedBase: Long = 0
    ): QuicScenario<NodeProgramFactory> =
        sampleGossip(
            name = SAMPLE_GOSSIP_100,
            nodeCount = 100,
            publishersCount = 100,
            neighboursToConnect = 20,
            topologySeed = topologySeed,
            gossipSeedBase = gossipSeedBase,
            latency = 100.milliseconds,
            messageSizeBytes = 180,
            eventSink = eventSink
        )

    fun sampleGossip20SyncPublish(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<NodeProgramFactory> =
        sampleGossip(
            name = SAMPLE_GOSSIP_20_SYNC_PUBLISH,
            nodeCount = 20,
            publishersCount = 20,
            connectToNodeIds = directedRingTopology(nodeCount = 20),
            latency = 100.milliseconds,
            messageSizeBytes = 180,
            initialPublishDelay = 30.seconds,
            maxRunDuration = 60.seconds,
            eventSink = eventSink
        )

    fun sampleGossip100LargeMessages(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink(),
        topologySeed: Int = 1234,
        gossipSeedBase: Long = 0
    ): QuicScenario<NodeProgramFactory> =
        sampleGossip(
            name = SAMPLE_GOSSIP_100_128K_10MS_5_PUBLISHERS,
            nodeCount = 100,
            publishersCount = 5,
            neighboursToConnect = 20,
            topologySeed = topologySeed,
            gossipSeedBase = gossipSeedBase,
            latency = 10.milliseconds,
            messageSizeBytes = 128 * 1024,
            eventSink = eventSink
        )

    fun byName(
        name: String,
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<NodeProgramFactory> =
        when (name) {
            SLOW_START -> slowStart(eventSink)
            SINGLE_TRANSFER_8MB -> singleTransfer8Mb(eventSink)
            SINGLE_TRANSFER_8MB_HALF_RECEIVER_BW -> singleTransfer8MbHalfReceiverBandwidth(eventSink)
            INBOUND_CONGESTION -> inboundCongestion(eventSink)
            SAMPLE_GOSSIP_100 -> sampleGossip100(eventSink)
            SAMPLE_GOSSIP_20_SYNC_PUBLISH -> sampleGossip20SyncPublish(eventSink)
            SAMPLE_GOSSIP_100_128K_10MS_5_PUBLISHERS -> sampleGossip100LargeMessages(eventSink)
            else -> throw IllegalArgumentException("Unknown QUIC scenario: $name")
        }

    private fun sampleGossip(
        name: String,
        nodeCount: Int,
        publishersCount: Int,
        neighboursToConnect: Int,
        topologySeed: Int = 1234,
        gossipSeedBase: Long = 0,
        latency: kotlin.time.Duration,
        messageSizeBytes: Int,
        initialPublishDelay: kotlin.time.Duration = 30.seconds,
        maxRunDuration: kotlin.time.Duration = 10.minutes,
        eventSink: QuicScenarioEventSink
    ): QuicScenario<NodeProgramFactory> =
        sampleGossip(
            name = name,
            nodeCount = nodeCount,
            publishersCount = publishersCount,
            connectToNodeIds = createBidirectionalRandomTopology(nodeCount, neighboursToConnect, seed = topologySeed),
            gossipSeedBase = gossipSeedBase,
            latency = latency,
            messageSizeBytes = messageSizeBytes,
            initialPublishDelay = initialPublishDelay,
            maxRunDuration = maxRunDuration,
            eventSink = eventSink
        )

    private fun sampleGossip(
        name: String,
        nodeCount: Int,
        publishersCount: Int,
        connectToNodeIds: Map<SimNodeId, List<SimNodeId>>,
        gossipSeedBase: Long = 0,
        latency: kotlin.time.Duration,
        messageSizeBytes: Int,
        initialPublishDelay: kotlin.time.Duration = 30.seconds,
        maxRunDuration: kotlin.time.Duration = 10.minutes,
        eventSink: QuicScenarioEventSink
    ): QuicScenario<NodeProgramFactory> =
        QuicScenario(
            name = name,
            network = QuicNetworkTopology.star(
                hostCount = nodeCount,
                latency = latency,
                bandwidthBytesPerSecond = 5_000_000L
            ),
            maxRunDuration = maxRunDuration,
            createNodeProgramFactory = {
                object : NodeProgramFactory, QuicScenarioEventSource {
                    override fun createNode(id: SimNodeId): NodeProgram =
                        SampleGossipNodeProgram(
                            simNodeId = id,
                            connectToNodeIds = connectToNodeIds.getValue(id),
                            publishersCount = publishersCount,
                            params = GossipParams(),
                            randomSeed = gossipSeedBase + id.toLong(),
                            messageSizeBytes = messageSizeBytes,
                            initialPublishDelay = initialPublishDelay,
                            eventSink = eventSink
                        )

                    override fun events(): List<QuicScenarioEvent> =
                        (eventSink as? QuicScenarioEventSource)?.events().orEmpty()
                }
            }
        )

    fun createBidirectionalRandomTopology(
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

        if (neighboursToConnect == 0) {
            return (0 until nodeCount).associateWith { emptyList() }
        }

        val random = Random(seed)
        val adjacency = MutableList(nodeCount) { mutableSetOf<Int>() }
        val edges = mutableListOf<Pair<Int, Int>>()

        val evenDegree = neighboursToConnect and 1.inv()
        val half = evenDegree / 2
        for (a in 0 until nodeCount) {
            for (step in 1..half) {
                val b = (a + step) % nodeCount
                addTopologyEdge(a, b, adjacency, edges)
            }
        }

        if ((neighboursToConnect and 1) == 1) {
            require(nodeCount % 2 == 0) { "Odd degree requires even nodeCount" }
            val halfNodes = nodeCount / 2
            for (i in 0 until halfNodes) {
                addTopologyEdge(i, i + halfNodes, adjacency, edges)
            }
        }

        val swapAttempts = maxOf(1_000, edges.size * 20)
        repeat(swapAttempts) {
            randomizeTopologyEdgePair(random, adjacency, edges)
        }

        check(adjacency.all { it.size == neighboursToConnect }) {
            "Failed to generate bidirectional topology: nodeCount=$nodeCount degree=$neighboursToConnect"
        }

        return adjacency
            .mapIndexed { nodeId, peers -> nodeId to peers.toList().sorted() }
            .toMap()
    }

    private fun directedRingTopology(nodeCount: Int): Map<SimNodeId, List<SimNodeId>> {
        require(nodeCount > 1) { "nodeCount must be greater than 1" }
        return (0 until nodeCount).associateWith { nodeId ->
            listOf((nodeId + 1) % nodeCount)
        }
    }

    private fun addTopologyEdge(
        a: Int,
        b: Int,
        adjacency: MutableList<MutableSet<Int>>,
        edges: MutableList<Pair<Int, Int>>
    ) {
        val edge = if (a < b) a to b else b to a
        if (adjacency[edge.first].add(edge.second)) {
            adjacency[edge.second] += edge.first
            edges += edge
        }
    }

    private fun randomizeTopologyEdgePair(
        random: Random,
        adjacency: MutableList<MutableSet<Int>>,
        edges: MutableList<Pair<Int, Int>>
    ) {
        if (edges.size < 2) return
        val firstIndex = random.nextInt(edges.size)
        var secondIndex = random.nextInt(edges.size - 1)
        if (secondIndex >= firstIndex) secondIndex++

        val first = edges[firstIndex]
        val second = edges[secondIndex]
        val (a, b) = first
        val (c, d) = second
        if (setOf(a, b, c, d).size < 4) return

        val candidate = if (random.nextBoolean()) {
            listOf(a to c, b to d)
        } else {
            listOf(a to d, b to c)
        }.map { (left, right) -> if (left < right) left to right else right to left }

        if (candidate[0] == candidate[1]) return
        if (candidate.any { (left, right) -> right in adjacency[left] }) return

        adjacency[a] -= b
        adjacency[b] -= a
        adjacency[c] -= d
        adjacency[d] -= c
        candidate.forEach { (left, right) ->
            adjacency[left] += right
            adjacency[right] += left
        }
        edges[firstIndex] = candidate[0]
        edges[secondIndex] = candidate[1]
    }
}
