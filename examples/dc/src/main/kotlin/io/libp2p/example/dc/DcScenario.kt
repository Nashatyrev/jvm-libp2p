package io.libp2p.example.dc

import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.runner.DatagramTrafficAggregate
import io.libp2p.quicsim.runner.SimulatedQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Block issuance: one block per slot, of a fixed [sizeBytes], published
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
 * A repeated-slot run of independently typed block, payload, column and FFG-attestation messages.
 * Every entry in [DcRunConfig.messages] is expanded over the compact slot cadence, so
 * callers configure a message once rather than describing each slot separately.
 *
 * A type may also be listed more than once, at different [DcSlotMessageConfig.publishOffset]s, to
 * issue it as several *waves* within each slot — "slot" being the repeating cycle, "wave" one
 * issuance inside it.
 *
 * [warmup] exists because gossipsub needs time to form its meshes; publishing before that measures
 * mesh construction rather than dissemination. [settle] is how long the run keeps going after the
 * last slot, and therefore the longest latency the run is able to observe — anything slower is
 * counted as an undelivered message instead of a large number.
 *
 * Both are kept short because they cost wall clock without measuring anything: a 1M-node run spent
 * 60 of its 71 simulated seconds outside the publishing window. 15s of warmup is ~5x how long that
 * run took to connect every node (3.2s) and a dozen heartbeats of mesh formation; 4s of settle is
 * ~2.5x the slowest delivery it observed (1.64s). Raise [settle] for a scenario that expects slower
 * tails — an undersized one silently reports them as lost.
 */
data class DcRunConfig(
    val slotCount: Int = 3,
    val warmup: Duration = 15.seconds,
    val slotInterval: Duration = 12.seconds,
    val settle: Duration = 4.seconds,
    /**
     * Leading slots left out of the headline figures in [DcRunReport.overall].
     *
     * QUIC congestion windows start small and meshes are still settling, so the first slots deliver
     * the same bytes more slowly than a running network would — measured at 2-3x the steady-state
     * p99 and up to 4x the steady-state max. They stay in [DcRunReport.perSlot] so the
     * ramp is still visible; they are only excluded from the aggregate.
     */
    val warmupSlots: Int = 0,
    /** Block issuance, or null for a run that issues no blocks. */
    val blocks: DcBlockConfig? = null,
    /** Global or subnet-scoped message kinds issued in every slot. */
    val messages: List<DcSlotMessageConfig> = emptyList(),
    /** Bucket width of [DcRunReport.slotTraffic], the per-message-type intra-slot profile. */
    val slotTrafficBucketDuration: Duration = 100.milliseconds,
    val gossipParams: GossipParams = GossipParams(),
    val randomSeed: Long = 0
) {
    init {
        require(warmupSlots in 0 until slotCount) {
            "warmupSlots must leave at least one measured slot, got $warmupSlots of $slotCount"
        }
        require(slotTrafficBucketDuration.isPositive()) {
            "slotTrafficBucketDuration must be > 0, got $slotTrafficBucketDuration"
        }
        val configs = messages + listOfNotNull(blocks?.asSlotMessageConfig())
        // Keyed on (type, publishOffset) rather than type alone, so one type may appear several
        // times to issue several waves within a slot -- one entry per offset into the slot. Two
        // entries of the same type at the same offset are still a mistake.
        require(configs.map { it.type to it.publishOffset }.distinct().size == configs.size) {
            "message type/publishOffset pairs must be unique, got " +
                "${configs.map { "${it.type}@${it.publishOffset}" }}"
        }
        require(configs.isNotEmpty()) { "a run must publish something; messages and blocks are both empty" }
        configs.forEach { message ->
            require(message.publishOffset < slotInterval) {
                "${message.type} publishOffset must be less than the $slotInterval slotInterval, " +
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

    /** Slot indices whose deliveries count toward the headline figures. */
    val measuredSlots: IntRange get() = warmupSlots until slotCount

    /** Compact repetition shared by all message configs; callers never list individual slots. */
    val slots: DcSlotCadence get() = DcSlotCadence(slotCount, warmup, slotInterval)
    val slotTimes: List<Duration>
        get() = slots.times

    /**
     * Moment every node stops, and the cut-off beyond which a delivery is counted as missing.
     *
     * Measured from the last thing published rather than from the last slot time, so a block
     * published part way into the final slot still gets the full [settle] window to arrive.
     */
    val completeAt: Duration
        get() = slotTimes.last() +
            (allMessageConfigs.maxOfOrNull { it.publishOffset } ?: Duration.ZERO) + settle

    internal val allMessageConfigs: List<DcSlotMessageConfig>
        get() = messages + listOfNotNull(blocks?.asSlotMessageConfig())

    /** Given to the simulator; slightly beyond [completeAt] so the run is not cut short. */
    val maxRunDuration: Duration get() = completeAt + 10.seconds
}

/** Creates the node programs and holds on to the recorder so results survive the run. */
class DcNodeProgramFactory<R>(
    private val network: DcNetwork<R>,
    private val graph: DcPeerGraph<R>,
    private val config: DcRunConfig,
    private val messageSchedules: List<DcSlotMessageSchedule>
) : NodeProgramFactory {

    /**
     * One recorder per message type, shared by every schedule of that type: a type issued as
     * several waves within a slot (one schedule per [DcSlotMessageConfig.publishOffset]) reports as
     * one merged set of publications and deliveries.
     */
    val messageRecorders = messageSchedules
        .map { it.config.type }
        .distinct()
        .associateWith { DcSlotMessageRecorder() }

    init {
        val keys = messageSchedules.map { it.config.type to it.config.publishOffset }
        require(keys.distinct().size == messageSchedules.size) {
            "Every slot-message schedule must have a distinct (type, publishOffset), got " +
                "${keys.map { (type, offset) -> "$type@$offset" }}"
        }
    }

    private val dialTargets = graph.dialTargets()
    private val nodePrograms = mutableListOf<DcNodeProgram>()

    override fun createNode(id: SimNodeId): NodeProgram =
        DcNodeProgram(
            simNodeId = id,
            connectToNodeIds = dialTargets.getValue(id),
            slotMessageSubnetIds = network.node(id).slotMessageSubnetIds,
            completeAt = config.completeAt,
            messageSchedules = messageSchedules,
            messageRecorders = messageRecorders,
            slotProfile = slotProfileParams,
            params = config.gossipParams,
            randomSeed = config.randomSeed + id
        ).also { nodePrograms += it }

    /** Shared by every node's [GossipByteCounter], so they all bucket wire bytes the same way. */
    private val slotProfileParams = DcSlotProfileParams(
        anchor = config.slotTimes.first(),
        slotDuration = config.slotInterval,
        bucketDuration = config.slotTrafficBucketDuration,
        warmupSlots = config.warmupSlots,
        slotCount = config.slotCount
    )

    /**
     * Everyone subscribed to this message's global or subnet topic, publisher included — the
     * denominator [expectedDeliveriesOf] and the per-group breakdown are both built from, so the
     * two stay consistent by construction rather than by two copies of the same branching.
     */
    fun subscribersOf(message: DcSlotMessage): List<DcNode<R>> =
        if (message.subnetId == null) {
            network.nodes
        } else {
            network.nodesSubscribedTo(message.type, message.subnetId)
        }

    /** Subscribers to this message's global or subnet topic, excluding its publisher. */
    fun expectedDeliveriesOf(message: DcSlotMessage): Int =
        subscribersOf(message).count { it.simNodeId != message.publisherNodeId }

    fun report(traffic: DcTrafficReport, datagrams: DatagramTrafficAggregate): DcRunReport {
        // One report per type, covering every wave of it: a type issued as several waves within a
        // slot has one schedule per wave, all recording into that type's single recorder.
        val schedulesByType = messageSchedules.groupBy { it.config.type }
        val reports = schedulesByType.mapValues { (type, schedules) ->
            val recorder = messageRecorders.getValue(type)
            DcSlotMessageReport.of(
                published = recorder.published(),
                deliveries = recorder.deliveries(),
                expectedDeliveriesOf = ::expectedDeliveriesOf,
                config = schedules.first().config,
                warmupSlots = config.warmupSlots,
                publishOffsets = schedules.map { it.config.publishOffset }.sorted()
            )
        }
        // FFG attestations are the headline figures when a run has them, being the thing most runs
        // exist to measure; a run without them falls back to its first configured type, and says
        // which it picked -- DcDeliveryStats carries the type id in its own output.
        val headlineType = DcSlotMessageType.FFG_ATTESTATION
            .takeIf { reports.containsKey(it) }
            ?: config.allMessageConfigs.first().type
        val headlineReport = reports.getValue(headlineType)
        val messagesByType = schedulesByType.keys.associateWith { type ->
            val recorder = messageRecorders.getValue(type)
            recorder.published() to recorder.deliveries()
        }
        val slotsMeasured = config.measuredSlots.count()
        val groups = DcGroupReport.of(
            network = network,
            trafficPerGroup = traffic.perGroup,
            gossipCounters = nodePrograms.associate { it.simNodeId to it.gossipByteCounter },
            meshSizes = nodePrograms.associate { it.simNodeId to it.finalMeshSizes },
            messagesByType = messagesByType,
            subscribersOf = ::subscribersOf,
            traffic = datagrams,
            slotProfileParams = slotProfileParams,
            slotsMeasured = slotsMeasured,
            warmupSlots = config.warmupSlots
        )
        val slotTraffic = DcSlotTrafficProfile.of(
            gossipCounters = nodePrograms.map { it.gossipByteCounter },
            traffic = datagrams,
            nodeIds = null,
            params = slotProfileParams,
            nodeCount = network.nodeCount,
            slotsMeasured = slotsMeasured
        )
        return DcRunReport(
            overall = headlineReport.overall,
            perSlot = headlineReport.perSlot,
            traffic = traffic,
            gossipBytesSent = nodePrograms.sumOf { it.gossipByteCounter.bytesWritten },
            gossipBytesReceived = nodePrograms.sumOf { it.gossipByteCounter.bytesRead },
            controlBreakdown = nodePrograms.fold(DcControlBreakdown.EMPTY) { acc, program ->
                acc + program.gossipByteCounter.controlBreakdownRead
            },
            gossipPublishBytesSent = nodePrograms.sumOf { it.gossipByteCounter.publishBytesWritten },
            gossipPublishBytesReceived = nodePrograms.sumOf { it.gossipByteCounter.publishBytesRead },
            gossipPublishBytesSentBySlot = nodePrograms.sumBySlot { it.publishBytesWrittenBySlot },
            gossipPublishBytesReceivedBySlot = nodePrograms.sumBySlot { it.publishBytesReadBySlot },
            gossipPublishMessagesSent = nodePrograms.sumOf { it.gossipByteCounter.publishMessagesWritten },
            gossipPublishMessagesReceived = nodePrograms.sumOf { it.gossipByteCounter.publishMessagesRead },
            gossipPublishMessagesSentBySlot = nodePrograms.sumBySlot { it.publishMessagesWrittenBySlot },
            gossipPublishMessagesReceivedBySlot = nodePrograms.sumBySlot { it.publishMessagesReadBySlot },
            warmupSlots = config.warmupSlots,
            mesh = DcMeshStats.of(nodePrograms.map { it.finalMeshSizes }),
            messages = reports,
            groups = groups,
            slotTraffic = slotTraffic
        )
    }
}

/** Totals each node's per-slot byte counts into one map keyed by slot index. */
private fun List<DcNodeProgram>.sumBySlot(
    counts: (GossipByteCounter) -> Map<Int, Long>
): Map<Int, Long> {
    val totals = mutableMapOf<Int, Long>()
    forEach { program ->
        counts(program.gossipByteCounter).forEach { (slot, bytes) ->
            totals[slot] = (totals[slot] ?: 0) + bytes
        }
    }
    return totals
}

object DcScenario {

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
        config: DcRunConfig = DcRunConfig(),
        messageSchedules: List<DcSlotMessageSchedule> = defaultMessageSchedules(network, config)
    ): QuicScenario<DcNodeProgramFactory<R>> {
        // One segment per type rather than per config, so a type issued as many waves within a slot
        // contributes "x12waves" instead of twelve near-identical segments.
        val messageSuffix = config.allMessageConfigs.groupBy { it.type }
            .entries
            .joinToString(separator = "") { (type, configs) ->
                val first = configs.first()
                val topics = when (val topicConfig = first.topics) {
                    DcSlotMessageTopics.Global -> "global"
                    is DcSlotMessageTopics.Subnets -> "${topicConfig.subnetCount}subnets"
                }
                val timing =
                    if (configs.size == 1) "@${first.publishOffset}" else "x${configs.size}waves"
                "-${type.id}${first.messagesPerSlot}x${first.sizeBytes}B$timing-$topics"
            }
        return QuicScenario(
            name = "dc-${network.nodeCount}n-${config.slotCount}slots$messageSuffix",
            network = network.topology,
            maxRunDuration = config.maxRunDuration,
            createNodeProgramFactory = {
                DcNodeProgramFactory(network, graph, config, messageSchedules)
            }
        )
    }

    /**
     * Builds one deterministic schedule per configured slot-message kind — meaning per
     * [DcSlotMessageConfig], so a type configured as several waves within a slot gets one schedule
     * per wave, each at its own [DcSlotMessageConfig.publishOffset].
     *
     * Message ids are handed out in non-overlapping ranges across all schedules, since a type's
     * waves share one recorder and are told apart by id — see
     * [DcSlotMessageSchedule.Companion.create]'s `firstMessageId`.
     */
    fun <R> defaultMessageSchedules(
        network: DcNetwork<R>,
        config: DcRunConfig
    ): List<DcSlotMessageSchedule> {
        val configs = config.allMessageConfigs
        // Waves of one type are numbered by their offset into the slot, earliest first, whatever
        // order the configs were listed in. The schedules themselves stay in listed order, so the
        // per-schedule random seeds below do not shift when a wave is added.
        val slotIndexOf = configs.groupBy { it.type }
            .flatMap { (_, ofType) ->
                ofType.sortedBy { it.publishOffset }.mapIndexed { wave, message -> message to wave }
            }
            .toMap()
        var nextMessageId = 0
        return configs.mapIndexed { index, message ->
            DcSlotMessageSchedule.create(
                network = network,
                slots = config.slots,
                config = message,
                randomSeed = config.randomSeed + index,
                firstMessageId = nextMessageId,
                waveIndex = slotIndexOf.getValue(message)
            ).also { nextMessageId += it.messages.size }
        }
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
        config: DcRunConfig = DcRunConfig(),
        latencyWindowParallelism: Int = Runtime.getRuntime().availableProcessors(),
        messageSchedules: List<DcSlotMessageSchedule> = defaultMessageSchedules(network, config)
    ): DcRunReport {
        // Folds each datagram into per-node, per-bucket counters instead of retaining it: a 500k
        // run traces hundreds of millions of them, and holding the events was what put a third of a
        // 330 GB heap out of reach. Resolution matches the slot profile's so its buckets fold whole.
        val traceRecorder = DatagramTrafficAggregate(
            bucketDuration = config.slotTrafficBucketDuration,
            nodeCount = network.nodeCount,
            runDuration = config.maxRunDuration
        )
        val result = SimulatedQuicScenarioRunner(
            latencyWindowParallelism = latencyWindowParallelism,
            datagramPacketTraceRecorder = traceRecorder
        ).run(of(network, graph, config, messageSchedules))
        val traffic = DcTrafficReport.of(
            traffic = traceRecorder,
            slotTimes = config.slotTimes,
            completeAt = config.completeAt,
            nodeCount = network.nodeCount,
            groupNodes = groupNodesOf(network)
        )
        return result.nodeProgramFactory.report(traffic, traceRecorder)
    }
}
