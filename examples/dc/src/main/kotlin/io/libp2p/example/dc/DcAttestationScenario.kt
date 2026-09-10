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
    val publishOffset: Duration = Duration.ZERO,
    /**
     * Names of the [DcNodeGroup]s whose validators may be drawn as proposers, or null — the
     * default — for every group in the network.
     *
     * Naming groups here restricts *who* proposes without changing the population: the same nodes
     * still run the same validators and carry the same attestation traffic, so a run can ask what
     * happens when blocks only ever come from, say, the datacenter-hosted pools. The draw within
     * the selected groups stays weighted by validator count.
     */
    val proposerGroups: Set<String>? = null
) {
    init {
        proposerGroups?.let {
            require(it.isNotEmpty()) {
                "proposerGroups must name at least one group; leave it null to allow every group"
            }
        }
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
            return DcSlotMessageConfig.maxSizeBytes(params)
        }
    }

    internal fun asSlotMessageConfig() = DcSlotMessageConfig(
        type = DcSlotMessageType.BLOCK,
        sizeBytes = sizeBytes,
        publishOffset = publishOffset,
        publisherGroups = proposerGroups,
        publisherSelection = DcPublisherSelection.VALIDATOR_WEIGHTED
    )
}

/**
 * A run in which, at each of several moments, a given number of randomly chosen validators publish
 * an attestation on one of their own subnets. [DcAttestationConfig.messages] can add independently
 * typed block, payload, column, or other message issuance to each of those slots.
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
    /** Arbitrary global-topic messages issued in every slot. */
    val messages: List<DcSlotMessageConfig> = emptyList(),
    val gossipParams: GossipParams = GossipParams(),
    val randomSeed: Long = 0
) {
    init {
        require(warmupWaves in 0 until waveCount) {
            "warmupWaves must leave at least one measured wave, got $warmupWaves of $waveCount"
        }
        val configs = messages + listOfNotNull(blocks?.asSlotMessageConfig())
        require(configs.map { it.type }.distinct().size == configs.size) {
            "message types must be unique, got ${configs.map { it.type }}"
        }
        configs.forEach { message ->
            require(message.publishOffset < waveInterval) {
                "${message.type} publishOffset must be less than the $waveInterval waveInterval, " +
                    "got ${message.publishOffset}"
            }
            val maxSize = DcSlotMessageConfig.maxSizeBytes(gossipParams)
            require(message.sizeBytes <= maxSize) {
                "${message.type} sizeBytes ${message.sizeBytes} exceeds what gossipsub will carry " +
                    "($maxSize) at maxGossipMessageSize=${gossipParams.maxGossipMessageSize}; raise " +
                    "maxGossipMessageSize on gossipParams to publish messages this large"
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
        get() = waveTimes.last() +
            (allMessageConfigs.maxOfOrNull { it.publishOffset } ?: Duration.ZERO) + settle

    internal val allMessageConfigs: List<DcSlotMessageConfig>
        get() = messages + listOfNotNull(blocks?.asSlotMessageConfig())

    /** Given to the simulator; slightly beyond [completeAt] so the run is not cut short. */
    val maxRunDuration: Duration get() = completeAt + 10.seconds
}

/** Creates the node programs and holds on to the recorder so results survive the run. */
class DcAttestationNodeProgramFactory<R>(
    private val network: DcNetwork<R>,
    private val graph: DcPeerGraph<R>,
    private val schedule: DcAttestationSchedule,
    private val config: DcAttestationConfig,
    private val messageSchedules: List<DcSlotMessageSchedule> = emptyList()
) : NodeProgramFactory {

    val recorder = DcAttestationRecorder()
    val messageRecorders = messageSchedules.associate { it.config.type to DcSlotMessageRecorder() }

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
            messageSchedules = messageSchedules,
            messageRecorders = messageRecorders,
            params = config.gossipParams,
            randomSeed = config.randomSeed + id
        ).also { nodePrograms += it }

    /**
     * Everyone subscribed to the subnet except the publisher. This is the denominator for the
     * delivery ratio, so it has to come from the population rather than from what arrived.
     */
    fun expectedDeliveriesOf(attestation: DcAttestation): Int =
        network.nodesSubscribedTo(attestation.subnetId).count { it.simNodeId != attestation.attesterNodeId }

    /** Every node but the publisher: slot-message topics are global. */
    val expectedMessageDeliveries: Int get() = network.nodeCount - 1

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
            messages = messageSchedules.associate { messageSchedule ->
                val recorder = messageRecorders.getValue(messageSchedule.config.type)
                messageSchedule.config.type to DcSlotMessageReport.of(
                    published = recorder.published(),
                    deliveries = recorder.deliveries(),
                    expectedDeliveriesPerMessage = expectedMessageDeliveries,
                    config = messageSchedule.config,
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
        messageSchedules: List<DcSlotMessageSchedule> = defaultMessageSchedules(network, config)
    ): QuicScenario<DcAttestationNodeProgramFactory<R>> {
        val messageSuffix = config.allMessageConfigs.joinToString(separator = "") {
            "-${it.type.id}${it.messagesPerSlot}x${it.sizeBytes}B@${it.publishOffset}"
        }
        return QuicScenario(
            name = "dc-attestations-${network.nodeCount}n-" +
                "${config.attestersPerWave}x${config.waveCount}-${config.attestationSizeBytes}B$messageSuffix",
            network = network.topology,
            maxRunDuration = config.maxRunDuration,
            createNodeProgramFactory = {
                DcAttestationNodeProgramFactory(network, graph, schedule, config, messageSchedules)
            }
        )
    }

    /** Builds one deterministic schedule for every configured slot-message kind. */
    fun <R> defaultMessageSchedules(
        network: DcNetwork<R>,
        config: DcAttestationConfig
    ): List<DcSlotMessageSchedule> = config.allMessageConfigs.mapIndexed { index, message ->
        DcSlotMessageSchedule.create(
            network = network,
            slotTimes = config.waveTimes,
            config = message,
            randomSeed = config.randomSeed + index
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
            proposerGroups = blocks.proposerGroups,
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
        messageSchedules: List<DcSlotMessageSchedule> = defaultMessageSchedules(network, config)
    ): DcAttestationReport {
        val traceRecorder = RecordingDatagramPacketTraceRecorder()
        val result = SimulatedQuicScenarioRunner(
            latencyWindowParallelism = latencyWindowParallelism,
            datagramPacketTraceRecorder = traceRecorder
        ).run(of(network, graph, config, schedule, messageSchedules))
        val traffic = DcTrafficReport.of(
            events = traceRecorder.events(),
            waveTimes = config.waveTimes,
            completeAt = config.completeAt,
            nodeCount = network.nodeCount
        )
        return result.nodeProgramFactory.report(traffic)
    }
}
