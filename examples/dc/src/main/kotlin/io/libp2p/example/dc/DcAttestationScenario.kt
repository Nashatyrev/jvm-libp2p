package io.libp2p.example.dc

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.runner.RecordingDatagramPacketTraceRecorder
import io.libp2p.quicsim.runner.SimulatedQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Block issuance: one block per wave — a wave being a slot — of a fixed [sizeBytes], published
 * [publishOffset] into the slot on the global block topic every node subscribes to.
 *
 * [publishOffset] is what makes a block land part way through a slot instead of exactly on its
 * boundary, the way a mainnet proposer publishes a second or two in: with attestations due at a
 * fixed point in the slot, moving the block later leaves less of the slot for it to reach everyone.
 *
 * [sizeBytes] is the block alone. Blobs are not modelled here — on mainnet they travel as separate
 * sidecar/column messages rather than inside the block — so this stays at the block body's own size
 * whatever the blob count.
 */
data class DcBlockConfig(
    val sizeBytes: Int = DEFAULT_SIZE_BYTES,
    val publishOffset: Duration = Duration.ZERO
) {
    init {
        require(sizeBytes >= DcMessagePayload.HEADER_BYTES) {
            "block sizeBytes must be at least ${DcMessagePayload.HEADER_BYTES}, got $sizeBytes"
        }
        require(!publishOffset.isNegative()) { "block publishOffset must be >= 0, got $publishOffset" }
    }

    companion object {
        /** Mainnet-ish beacon block body, and the size every reference config uses by default. */
        const val DEFAULT_SIZE_BYTES: Int = 128 * 1024

        /**
         * Largest block that gossipsub will actually carry under [params].
         *
         * Outbound RPCs are split to stay under `maxGossipMessageSize` less a margin of 1% (at
         * least 64 bytes), and inbound frames above `maxGossipMessageSize` are dropped by the frame
         * decoder — so the default 1 MiB limit cannot carry a 1 MiB block. [FRAMING_BYTES] covers
         * the RPC and topic bytes wrapped around the payload.
         */
        fun maxSizeBytes(params: GossipParams): Int {
            val margin = maxOf(64, params.maxGossipMessageSize / 100)
            return params.maxGossipMessageSize - margin - FRAMING_BYTES
        }

        /** Headroom for the publish/RPC protobuf framing and the topic string around a payload. */
        private const val FRAMING_BYTES: Int = 256
    }
}

/**
 * A run in which, at each of several moments, a given number of randomly chosen validators publish
 * an attestation on one of their own subnets. When [DcAttestationConfig.blocks] is set, each of
 * those moments also has one proposer publish a block.
 *
 * [warmup] exists because gossipsub needs time to form its meshes; attesting before that measures
 * mesh construction rather than dissemination. [settle] is how long the run keeps going after the
 * last wave, and therefore the longest latency the run is able to observe — anything slower is
 * counted as an undelivered attestation instead of a large number.
 */
data class DcAttestationConfig(
    val waveCount: Int = 3,
    val attestersPerWave: Int = 32,
    val attestationSizeBytes: Int = 240,
    val warmup: Duration = 30.seconds,
    val waveInterval: Duration = 12.seconds,
    val settle: Duration = 12.seconds,
    /**
     * Leading waves left out of the headline figures in [DcAttestationReport.overall].
     *
     * QUIC congestion windows start small and meshes are still settling, so the first waves deliver
     * the same bytes more slowly than a running network would — measured at 2-3x the steady-state
     * p99 and up to 4x the steady-state max. They stay in [DcAttestationReport.perWave] so the
     * ramp is still visible; they are only excluded from the aggregate.
     */
    val warmupWaves: Int = 0,
    /** Block issuance, or null for a run that publishes attestations only. */
    val blocks: DcBlockConfig? = null,
    val gossipParams: GossipParams = GossipParams(),
    val randomSeed: Long = 0
) {
    init {
        require(warmupWaves in 0 until waveCount) {
            "warmupWaves must leave at least one measured wave, got $warmupWaves of $waveCount"
        }
        blocks?.let { block ->
            // A block published later than one slot in would arrive after the next slot's block,
            // which is not a slot schedule any more.
            require(block.publishOffset < waveInterval) {
                "block publishOffset must be less than the $waveInterval waveInterval, " +
                    "got ${block.publishOffset}"
            }
            val maxSize = DcBlockConfig.maxSizeBytes(gossipParams)
            require(block.sizeBytes <= maxSize) {
                "block sizeBytes ${block.sizeBytes} exceeds what gossipsub will carry ($maxSize) at " +
                    "maxGossipMessageSize=${gossipParams.maxGossipMessageSize}; raise " +
                    "maxGossipMessageSize on gossipParams to publish blocks this large"
            }
        }
    }

    /** Wave indices whose deliveries count toward the headline figures. */
    val measuredWaves: IntRange get() = warmupWaves until waveCount
    val waveTimes: List<Duration>
        get() = DcAttestationSchedule.waveTimes(waveCount, warmup, waveInterval)

    /**
     * Moment every node stops, and the cut-off beyond which a delivery is counted as missing.
     *
     * Measured from the last thing published rather than from the last wave time, so a block
     * published part way into the final slot still gets the full [settle] window to arrive.
     */
    val completeAt: Duration
        get() = waveTimes.last() + (blocks?.publishOffset ?: Duration.ZERO) + settle

    /** Given to the simulator; slightly beyond [completeAt] so the run is not cut short. */
    val maxRunDuration: Duration get() = completeAt + 10.seconds
}

/** Creates the node programs and holds on to the recorder so results survive the run. */
class DcAttestationNodeProgramFactory<R>(
    private val network: DcNetwork<R>,
    private val graph: DcPeerGraph<R>,
    private val schedule: DcAttestationSchedule,
    private val config: DcAttestationConfig,
    private val blockSchedule: DcBlockSchedule? = null
) : NodeProgramFactory {

    val recorder = DcAttestationRecorder()
    val blockRecorder = DcBlockRecorder()

    private val dialTargets = graph.dialTargets()
    private val nodePrograms = mutableListOf<DcAttestationNodeProgram>()

    override fun createNode(id: SimNodeId): NodeProgram =
        DcAttestationNodeProgram(
            simNodeId = id,
            connectToNodeIds = dialTargets.getValue(id),
            subnetIds = network.node(id).attestationSubnetIds,
            schedule = schedule,
            recorder = recorder,
            attestationSizeBytes = config.attestationSizeBytes,
            completeAt = config.completeAt,
            blockSchedule = blockSchedule,
            blockRecorder = blockRecorder,
            blockSizeBytes = config.blocks?.sizeBytes ?: 0,
            params = config.gossipParams,
            randomSeed = config.randomSeed + id
        ).also { nodePrograms += it }

    /**
     * Everyone subscribed to the subnet except the publisher. This is the denominator for the
     * delivery ratio, so it has to come from the population rather than from what arrived.
     */
    fun expectedDeliveriesOf(attestation: DcAttestation): Int =
        network.nodesSubscribedTo(attestation.subnetId).count { it.simNodeId != attestation.attesterNodeId }

    /** Every node but the proposer: the block topic is global, so the whole network expects it. */
    val expectedBlockDeliveries: Int get() = network.nodeCount - 1

    fun report(traffic: DcTrafficReport): DcAttestationReport =
        DcAttestationReport.of(
            published = recorder.published(),
            deliveries = recorder.deliveries(),
            expectedDeliveriesOf = ::expectedDeliveriesOf,
            traffic = traffic,
            gossipBytesSent = nodePrograms.sumOf { it.gossipByteCounter.bytesWritten },
            gossipBytesReceived = nodePrograms.sumOf { it.gossipByteCounter.bytesRead },
            gossipPublishBytesSent = nodePrograms.sumOf { it.gossipByteCounter.publishBytesWritten },
            gossipPublishBytesReceived = nodePrograms.sumOf { it.gossipByteCounter.publishBytesRead },
            gossipPublishBytesSentByWave = nodePrograms.sumByWave { it.publishBytesWrittenByWave },
            gossipPublishBytesReceivedByWave = nodePrograms.sumByWave { it.publishBytesReadByWave },
            gossipPublishMessagesSent = nodePrograms.sumOf { it.gossipByteCounter.publishMessagesWritten },
            gossipPublishMessagesReceived = nodePrograms.sumOf { it.gossipByteCounter.publishMessagesRead },
            gossipPublishMessagesSentByWave = nodePrograms.sumByWave { it.publishMessagesWrittenByWave },
            gossipPublishMessagesReceivedByWave = nodePrograms.sumByWave { it.publishMessagesReadByWave },
            warmupWaves = config.warmupWaves,
            mesh = DcMeshStats.of(nodePrograms.map { it.finalMeshSizes }),
            blocks = blockSchedule?.let {
                DcBlockReport.of(
                    published = blockRecorder.published(),
                    deliveries = blockRecorder.deliveries(),
                    expectedDeliveriesPerBlock = expectedBlockDeliveries,
                    sizeBytes = config.blocks?.sizeBytes ?: 0,
                    publishOffset = it.publishOffset,
                    warmupWaves = config.warmupWaves
                )
            }
        )
}

/** Totals each node's per-wave byte counts into one map keyed by wave index. */
private fun List<DcAttestationNodeProgram>.sumByWave(
    counts: (GossipByteCounter) -> Map<Int, Long>
): Map<Int, Long> {
    val totals = mutableMapOf<Int, Long>()
    forEach { program ->
        counts(program.gossipByteCounter).forEach { (wave, bytes) ->
            totals[wave] = (totals[wave] ?: 0) + bytes
        }
    }
    return totals
}

object DcAttestationScenario {

    /**
     * Builds the scenario. Connections come from [DcPeerGraph.dialTargets] so each edge is dialled
     * once, and the subnet coverage guarantee of the graph carries into the run.
     */
    fun <R> of(
        network: DcNetwork<R>,
        graph: DcPeerGraph<R>,
        config: DcAttestationConfig = DcAttestationConfig(),
        schedule: DcAttestationSchedule = DcAttestationSchedule.random(
            network = network,
            waveTimes = config.waveTimes,
            attestersPerWave = config.attestersPerWave,
            randomSeed = config.randomSeed
        ),
        blockSchedule: DcBlockSchedule? = defaultBlockSchedule(network, config)
    ): QuicScenario<DcAttestationNodeProgramFactory<R>> {
        val blockSuffix = config.blocks?.let { "-block${it.sizeBytes}B@${it.publishOffset}" } ?: ""
        return QuicScenario(
            name = "dc-attestations-${network.nodeCount}n-" +
                "${config.attestersPerWave}x${config.waveCount}-${config.attestationSizeBytes}B$blockSuffix",
            network = network.topology,
            maxRunDuration = config.maxRunDuration,
            createNodeProgramFactory = {
                DcAttestationNodeProgramFactory(network, graph, schedule, config, blockSchedule)
            }
        )
    }

    /**
     * One validator-weighted proposer per wave when [DcAttestationConfig.blocks] asks for blocks,
     * and no block schedule at all when it does not — so a run that says nothing about blocks
     * behaves exactly as it did before they existed.
     */
    fun <R> defaultBlockSchedule(
        network: DcNetwork<R>,
        config: DcAttestationConfig
    ): DcBlockSchedule? = config.blocks?.let { blocks ->
        DcBlockSchedule.validatorWeighted(
            network = network,
            waveTimes = config.waveTimes,
            publishOffset = blocks.publishOffset,
            randomSeed = config.randomSeed
        )
    }

    /**
     * Runs the scenario on the deterministic simulator and returns the delivery-latency and traffic
     * report. Traffic is captured via a [RecordingDatagramPacketTraceRecorder], which taps every raw
     * UDP datagram sent or received by every node — so the traffic figures include QUIC's own
     * overhead (handshakes, ACKs, retransmits), not just gossip payload bytes.
     */
    fun <R> run(
        network: DcNetwork<R>,
        graph: DcPeerGraph<R>,
        config: DcAttestationConfig = DcAttestationConfig(),
        latencyWindowParallelism: Int = Runtime.getRuntime().availableProcessors(),
        schedule: DcAttestationSchedule = DcAttestationSchedule.random(
            network = network,
            waveTimes = config.waveTimes,
            attestersPerWave = config.attestersPerWave,
            randomSeed = config.randomSeed
        ),
        blockSchedule: DcBlockSchedule? = defaultBlockSchedule(network, config)
    ): DcAttestationReport {
        val traceRecorder = RecordingDatagramPacketTraceRecorder()
        val result = SimulatedQuicScenarioRunner(
            latencyWindowParallelism = latencyWindowParallelism,
            datagramPacketTraceRecorder = traceRecorder
        ).run(of(network, graph, config, schedule, blockSchedule))
        val traffic = DcTrafficReport.of(
            events = traceRecorder.events(),
            waveTimes = config.waveTimes,
            completeAt = config.completeAt,
            nodeCount = network.nodeCount
        )
        return result.nodeProgramFactory.report(traffic)
    }
}
