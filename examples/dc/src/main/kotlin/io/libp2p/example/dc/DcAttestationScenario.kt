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
 * A repeated-wave run of independently typed block, payload, column, FFG-attestation, or custom
 * messages. Every entry in [DcAttestationConfig.messages] is expanded over the compact wave
 * definition, so callers configure a message once rather than describing each wave separately.
 *
 * [warmup] exists because gossipsub needs time to form its meshes; attesting before that measures
 * mesh construction rather than dissemination. [settle] is how long the run keeps going after the
 * last wave, and therefore the longest latency the run is able to observe — anything slower is
 * counted as an undelivered attestation instead of a large number.
 */
data class DcAttestationConfig(
    val waveCount: Int = 3,
    /** Compatibility input used only when [messages] has no explicit FFG-attestation entry. */
    val attestersPerWave: Int = 32,
    /** Compatibility input used only when [messages] has no explicit FFG-attestation entry. */
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
    /** Global or subnet-scoped message kinds issued in every wave. */
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

    /** Compact repetition shared by all message configs; callers never list individual waves. */
    val waves: DcSlotMessageWaves get() = DcSlotMessageWaves(waveCount, warmup, waveInterval)
    val waveTimes: List<Duration>
        get() = waves.times

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
    private val schedule: DcAttestationSchedule?,
    private val config: DcAttestationConfig,
    private val messageSchedules: List<DcSlotMessageSchedule> = emptyList()
) : NodeProgramFactory {

    private val legacyFfgSchedule = schedule?.asSlotMessageSchedule(
        sizeBytes = config.attestationSizeBytes,
        subnetCount = (network.attestationSubnetIds().maxOrNull() ?: -1) + 1
    )
    private val allMessageSchedules = listOfNotNull(legacyFfgSchedule) + messageSchedules
    val messageRecorders = allMessageSchedules.associate { it.config.type to DcSlotMessageRecorder() }

    init {
        require(allMessageSchedules.count { it.config.type == DcSlotMessageType.FFG_ATTESTATION } == 1) {
            "A scenario must contain exactly one FFG attestation schedule"
        }
        require(messageRecorders.size == allMessageSchedules.size) {
            "Every slot-message schedule must have a distinct type"
        }
    }

    private val dialTargets = graph.dialTargets()
    private val nodePrograms = mutableListOf<DcAttestationNodeProgram>()

    override fun createNode(id: SimNodeId): NodeProgram =
        DcAttestationNodeProgram(
            simNodeId = id,
            connectToNodeIds = dialTargets.getValue(id),
            slotMessageSubnetIds = network.node(id).slotMessageSubnetIds.let { subscriptions ->
                if (DcSlotMessageType.FFG_ATTESTATION in subscriptions) {
                    subscriptions
                } else {
                    subscriptions +
                        (DcSlotMessageType.FFG_ATTESTATION to network.node(id).attestationSubnetIds)
                }
            },
            completeAt = config.completeAt,
            messageSchedules = allMessageSchedules,
            messageRecorders = messageRecorders,
            params = config.gossipParams,
            randomSeed = config.randomSeed + id
        ).also { nodePrograms += it }

    /**
     * Everyone subscribed to this message's global or subnet topic, publisher included — the
     * denominator [expectedDeliveriesOf] and the per-group breakdown are both built from, so the
     * two stay consistent by construction rather than by two copies of the same branching.
     */
    fun subscribersOf(message: DcSlotMessage): List<DcNode<R>> =
        if (message.subnetId == null) {
            network.nodes
        } else if (message.type == DcSlotMessageType.FFG_ATTESTATION) {
            val explicitFfgSubnets = network.messageSubnetIds(DcSlotMessageType.FFG_ATTESTATION)
            if (explicitFfgSubnets.isEmpty()) {
                network.nodesSubscribedTo(message.subnetId)
            } else {
                network.nodesSubscribedTo(DcSlotMessageType.FFG_ATTESTATION, message.subnetId)
            }
        } else {
            network.nodesSubscribedTo(message.type, message.subnetId)
        }

    /** Subscribers to this message's global or subnet topic, excluding its publisher. */
    fun expectedDeliveriesOf(message: DcSlotMessage): Int =
        subscribersOf(message).count { it.simNodeId != message.publisherNodeId }

    fun report(traffic: DcTrafficReport): DcAttestationReport {
        val reports = allMessageSchedules.associate { messageSchedule ->
            val recorder = messageRecorders.getValue(messageSchedule.config.type)
            messageSchedule.config.type to DcSlotMessageReport.of(
                published = recorder.published(),
                deliveries = recorder.deliveries(),
                expectedDeliveriesOf = ::expectedDeliveriesOf,
                config = messageSchedule.config,
                warmupWaves = config.warmupWaves
            )
        }
        val ffgReport = reports.getValue(DcSlotMessageType.FFG_ATTESTATION)
        val groups = DcGroupReport.of(
            network = network,
            trafficPerGroup = traffic.perGroup,
            gossipCounters = nodePrograms.associate { it.simNodeId to it.gossipByteCounter },
            meshSizes = nodePrograms.associate { it.simNodeId to it.finalMeshSizes },
            messagesByType = allMessageSchedules.associate { messageSchedule ->
                val recorder = messageRecorders.getValue(messageSchedule.config.type)
                messageSchedule.config.type to (recorder.published() to recorder.deliveries())
            },
            subscribersOf = ::subscribersOf,
            warmupWaves = config.warmupWaves
        )
        return DcAttestationReport(
            overall = ffgReport.overall,
            perWave = ffgReport.perSlot,
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
            messages = reports,
            blocks = reports[DcSlotMessageType.BLOCK]?.asBlockReport(),
            groups = groups
        )
    }
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
     * Node ids by group name, for [DcTrafficReport]'s per-group traffic breakdown. Empty when
     * nothing in the network was named — see [DcNodeGroup.name] — since there is then nothing to
     * break down; otherwise every node is bucketed, unnamed ones under [DcGroupStats.UNNAMED], so
     * the groups still partition the network and their traffic sums to the whole-run total.
     */
    private fun <R> groupNodesOf(network: DcNetwork<R>): Map<String, Set<SimNodeId>> =
        if (network.groupNames().isEmpty()) {
            emptyMap()
        } else {
            network.nodes.groupBy { it.groupName ?: DcGroupStats.UNNAMED }
                .mapValues { (_, nodes) -> nodes.mapTo(hashSetOf()) { it.simNodeId } }
        }

    /**
     * Builds the scenario. Connections come from [DcPeerGraph.dialTargets] so each edge is dialled
     * once, and the subnet coverage guarantee of the graph carries into the run.
     */
    fun <R> of(
        network: DcNetwork<R>,
        graph: DcPeerGraph<R>,
        config: DcAttestationConfig = DcAttestationConfig(),
        schedule: DcAttestationSchedule? = defaultAttestationSchedule(network, config),
        messageSchedules: List<DcSlotMessageSchedule> = defaultMessageSchedules(network, config)
    ): QuicScenario<DcAttestationNodeProgramFactory<R>> {
        val messageSuffix = config.allMessageConfigs.joinToString(separator = "") {
            val topics = when (val topicConfig = it.topics) {
                DcSlotMessageTopics.Global -> "global"
                is DcSlotMessageTopics.Subnets -> "${topicConfig.subnetCount}subnets"
            }
            "-${it.type.id}${it.messagesPerSlot}x${it.sizeBytes}B@${it.publishOffset}-$topics"
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
            waves = config.waves,
            config = message,
            randomSeed = config.randomSeed + index
        )
    }

    /** Legacy attestation selection, omitted when FFG is configured as an ordinary message type. */
    fun <R> defaultAttestationSchedule(
        network: DcNetwork<R>,
        config: DcAttestationConfig
    ): DcAttestationSchedule? =
        if (config.allMessageConfigs.any { it.type == DcSlotMessageType.FFG_ATTESTATION }) {
            null
        } else {
            DcAttestationSchedule.random(
                network = network,
                waveTimes = config.waveTimes,
                attestersPerWave = config.attestersPerWave,
                randomSeed = config.randomSeed
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
        schedule: DcAttestationSchedule? = defaultAttestationSchedule(network, config),
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
            nodeCount = network.nodeCount,
            groupNodes = groupNodesOf(network)
        )
        return result.nodeProgramFactory.report(traffic)
    }
}
