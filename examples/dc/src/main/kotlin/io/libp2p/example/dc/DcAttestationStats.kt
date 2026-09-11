package io.libp2p.example.dc

import io.libp2p.quicsim.runner.DatagramPacketTraceEvent
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.time.Duration

/**
 * Delivery latency of attestations.
 *
 * [deliveryRatio] matters as much as the percentiles: percentiles are computed over deliveries that
 * *happened*, so a run that loses slow messages would otherwise look faster than one that delivers
 * everything. Check it is 1.0 before reading anything into p99.
 */
data class DcDeliveryStats(
    val publishedCount: Int,
    val expectedDeliveries: Int,
    val actualDeliveries: Int,
    val p50: Duration?,
    val p95: Duration?,
    val p99: Duration?,
    val min: Duration?,
    val max: Duration?,
    val mean: Duration?,
    /** What was published, for the first line of [toString]; the maths is the same either way. */
    val what: String = "attestations"
) {
    val deliveryRatio: Double
        get() = if (expectedDeliveries == 0) 0.0 else actualDeliveries.toDouble() / expectedDeliveries

    val missingDeliveries: Int get() = expectedDeliveries - actualDeliveries

    override fun toString(): String = buildString {
        appendLine(
            "$what=$publishedCount deliveries=$actualDeliveries/$expectedDeliveries " +
                "(${"%.2f".format(deliveryRatio * 100)}%)"
        )
        appendLine(
            "latency: p50=${p50.ms()} p95=${p95.ms()} p99=${p99.ms()} " +
                "min=${min.ms()} max=${max.ms()} mean=${mean.ms()}"
        )
    }

    private fun Duration?.ms(): String =
        if (this == null) "-" else "${inWholeMicroseconds / 1000.0}ms"

    companion object {
        /**
         * [expectedDeliveries] is passed in rather than derived from [latencies], since the whole
         * point is to notice deliveries that never arrived.
         */
        fun of(
            publishedCount: Int,
            latencies: List<Duration>,
            expectedDeliveries: Int,
            what: String = "attestations"
        ): DcDeliveryStats {
            val sorted = latencies.sorted()
            return DcDeliveryStats(
                publishedCount = publishedCount,
                expectedDeliveries = expectedDeliveries,
                actualDeliveries = sorted.size,
                p50 = sorted.percentile(0.50),
                p95 = sorted.percentile(0.95),
                p99 = sorted.percentile(0.99),
                min = sorted.firstOrNull(),
                max = sorted.lastOrNull(),
                mean = sorted.meanOrNull(),
                what = what
            )
        }
    }
}

/** Delivery statistics for one configured kind of slot message. */
data class DcSlotMessageReport(
    val type: DcSlotMessageType,
    val overall: DcDeliveryStats,
    val perSlot: Map<Int, DcDeliveryStats>,
    val config: DcSlotMessageConfig,
    val publishTimes: Map<Int, List<Duration>>,
    val publishers: Map<Int, Set<SimNodeId>>,
    val warmupWaves: Int = 0,
    /**
     * Every offset into the slot this type is issued at, in order — one entry per
     * [DcSlotMessageConfig] of this type, so a single-wave type has just
     * [DcSlotMessageConfig.publishOffset]. [config] is the first of those configs; the rest differ
     * only in their offset.
     */
    val publishOffsets: List<Duration> = listOf(config.publishOffset),
    /**
     * Stats per wave within the slot, keyed by [DcSlotMessage.waveIndexInSlot], for a type issued as
     * several waves — so a wave that lands while the slot is already busy can be told apart from one
     * that has the network to itself. Holds a single entry for a single-wave type, where it says the
     * same thing as [overall].
     */
    val perWaveInSlot: Map<Int, DcDeliveryStats> = emptyMap()
) {
    val measuredSlots: List<Int> get() = perSlot.keys.filter { it >= warmupWaves }.sorted()

    override fun toString(): String = buildString {
        appendLine(
            "%s: %d x %d B per slot, %s topics=%s publishers=%s selection=%s".format(
                type.id,
                config.messagesPerSlot,
                config.sizeBytes,
                offsetsDescription(),
                config.topics,
                config.publisherGroups?.joinToString(prefix = "groups ") ?: "all groups",
                config.publisherSelection
            )
        )
        append("$type overall: $overall")
        if (perWaveInSlot.size > 1) {
            perWaveInSlot.toSortedMap().forEach { (wave, stats) ->
                val at = publishOffsets.getOrNull(wave)?.let { " @$it" }.orEmpty()
                append("$type wave $wave of ${perWaveInSlot.size} in slot$at: $stats")
            }
        }
        perSlot.toSortedMap().forEach { (slot, stats) ->
            val tag = if (slot < warmupWaves) " [warmup, excluded from overall]" else ""
            val publisher = publishers[slot]?.let { ids ->
                if (ids.size == 1) {
                    " publisher=node-${ids.single()}"
                } else {
                    " publishers=${ids.size} nodes"
                }
            }.orEmpty()
            val at = publishTimes[slot]?.firstOrNull()?.let { " publishedAt=$it" }.orEmpty()
            append("$type slot $slot$tag$publisher$at: $stats")
        }
    }

    private fun offsetsDescription(): String =
        if (publishOffsets.size <= 1) {
            "publishOffset=${publishOffsets.singleOrNull() ?: config.publishOffset}"
        } else {
            "waves=${publishOffsets.size} publishOffsets=${publishOffsets.first()}..${publishOffsets.last()}"
        }

    companion object {
        fun of(
            published: List<DcSlotMessagePublication>,
            deliveries: List<DcSlotMessageDelivery>,
            expectedDeliveriesOf: (DcSlotMessage) -> Int,
            config: DcSlotMessageConfig,
            warmupWaves: Int = 0,
            publishOffsets: List<Duration> = listOf(config.publishOffset)
        ): DcSlotMessageReport {
            val deliveriesByWave = deliveries.groupBy { it.slotIndex }
            val publicationsByWave = published.groupBy { it.message.slotIndex }
            val measuredPublished = published.filter { it.message.slotIndex >= warmupWaves }
            val measuredDeliveries = deliveries.filter { it.slotIndex >= warmupWaves }
            // Deliveries carry only the message id, so the wave they belong to is looked up from the
            // publisher side -- sound because ids are unique across a type's waves, see
            // DcSlotMessageSchedule.create's firstMessageId.
            val waveOfMessageId = measuredPublished.associate { it.message.id to it.message.waveIndexInSlot }
            val measuredByWaveInSlot = measuredPublished.groupBy { it.message.waveIndexInSlot }
            val deliveriesByWaveInSlot = measuredDeliveries.groupBy { waveOfMessageId[it.messageId] }
            return DcSlotMessageReport(
                type = config.type,
                overall = DcDeliveryStats.of(
                    publishedCount = measuredPublished.size,
                    latencies = measuredDeliveries.map { it.latency },
                    expectedDeliveries = measuredPublished.sumOf { expectedDeliveriesOf(it.message) },
                    what = config.type.id
                ),
                perSlot = publicationsByWave.mapValues { (wave, wavePublications) ->
                    DcDeliveryStats.of(
                        publishedCount = wavePublications.size,
                        latencies = deliveriesByWave[wave].orEmpty().map { it.latency },
                        expectedDeliveries = wavePublications.sumOf { expectedDeliveriesOf(it.message) },
                        what = config.type.id
                    )
                },
                config = config,
                publishTimes = publicationsByWave.mapValues { (_, values) ->
                    values.map { it.publishedAt }
                },
                publishers = publicationsByWave.mapValues { (_, values) ->
                    values.mapTo(linkedSetOf()) { it.message.publisherNodeId }
                },
                warmupWaves = warmupWaves,
                publishOffsets = publishOffsets,
                perWaveInSlot = measuredByWaveInSlot.mapValues { (wave, wavePublications) ->
                    DcDeliveryStats.of(
                        publishedCount = wavePublications.size,
                        latencies = deliveriesByWaveInSlot[wave].orEmpty().map { it.latency },
                        expectedDeliveries = wavePublications.sumOf { expectedDeliveriesOf(it.message) },
                        what = config.type.id
                    )
                }
            )
        }
    }
}

/** Stats for the whole run plus a breakdown per wave, so a slow wave does not hide in the average. */
data class DcAttestationReport(
    val overall: DcDeliveryStats,
    val perWave: Map<Int, DcDeliveryStats>,
    val traffic: DcTrafficReport,
    val gossipBytesSent: Long = 0,
    val gossipBytesReceived: Long = 0,
    val gossipPublishBytesSent: Long = 0,
    val gossipPublishBytesReceived: Long = 0,
    val gossipPublishBytesSentByWave: Map<Int, Long> = emptyMap(),
    val gossipPublishBytesReceivedByWave: Map<Int, Long> = emptyMap(),
    val gossipPublishMessagesSent: Long = 0,
    val gossipPublishMessagesReceived: Long = 0,
    val gossipPublishMessagesSentByWave: Map<Int, Long> = emptyMap(),
    val gossipPublishMessagesReceivedByWave: Map<Int, Long> = emptyMap(),
    /** Leading waves excluded from [overall]; see [DcAttestationConfig.warmupWaves]. */
    val warmupWaves: Int = 0,
    val mesh: DcMeshStats? = null,
    /**
     * Separate reports keyed by [DcSlotMessageConfig.type]. Block issuance ([DcAttestationConfig
     * .blocks]) shows up here too, at [DcSlotMessageType.BLOCK] -- it is folded into the same
     * generic message pipeline as every other type, so there is no separate block-shaped report.
     */
    val messages: Map<DcSlotMessageType, DcSlotMessageReport> = emptyMap(),
    /** Per-group breakdown; empty unless a group in the network was named. See [DcNodeGroup.name]. */
    val groups: DcGroupReport = DcGroupReport(emptyMap()),
    /** Where in the slot each message type's bytes land; see [DcAttestationConfig.slotTrafficBucketDuration]. */
    val slotTraffic: DcSlotTrafficProfile? = null
) {
    val gossipControlBytesSent: Long get() = gossipBytesSent - gossipPublishBytesSent
    val gossipControlBytesReceived: Long get() = gossipBytesReceived - gossipPublishBytesReceived

    /** Wave indices behind [overall], i.e. every wave except the warm-up ones. */
    val measuredWaves: List<Int> get() = perWave.keys.filter { it >= warmupWaves }.sorted()

    private fun measuredSum(byWave: Map<Int, Long>): Long =
        byWave.entries.filter { it.key >= warmupWaves }.sumOf { it.value }

    /** Publish bytes for the measured waves only, so they line up with [overall]. */
    val measuredPublishBytesReceived: Long get() = measuredSum(gossipPublishBytesReceivedByWave)
    val measuredPublishBytesSent: Long get() = measuredSum(gossipPublishBytesSentByWave)

    /**
     * Publish bytes one node receives for one wave. Unlike a whole-run total this does not move
     * when [DcAttestationConfig.waveCount] or [DcAttestationConfig.warmupWaves] change, so it is
     * the load figure to compare across runs.
     */
    val publishBytesReceivedPerNodePerWave: Double
        get() {
            val waves = measuredWaves.size
            val nodes = traffic.overall.nodeCount
            return if (waves == 0 || nodes == 0) {
                0.0
            } else {
                measuredPublishBytesReceived.toDouble() / waves / nodes
            }
        }

    /**
     * Copies of each message a node receives, per copy it actually needed. 1.0 would mean every
     * message arrived exactly once; gossipsub pays about one copy per mesh peer, since every mesh
     * peer forwards a new message before learning the recipient already has it.
     *
     * Measured rather than inferred: both terms are counts, so no per-message size is assumed.
     * Restricted to the measured waves so numerator and denominator cover the same waves.
     */
    val duplicationFactor: Double
        get() = if (overall.actualDeliveries == 0) {
            0.0
        } else {
            measuredSum(gossipPublishMessagesReceivedByWave).toDouble() / overall.actualDeliveries
        }

    /** Mean wire size of one published message, including gossip framing. */
    val gossipBytesPerPublishedMessage: Double
        get() {
            val msgs = measuredSum(gossipPublishMessagesReceivedByWave)
            return if (msgs == 0L) 0.0 else measuredPublishBytesReceived.toDouble() / msgs
        }

    override fun toString(): String = buildString {
        val scope =
            if (warmupWaves == 0) "all waves" else "waves ${measuredWaves.firstOrNull()}-${measuredWaves.lastOrNull()}"
        append("overall ($scope): $overall")
        perWave.toSortedMap().forEach { (wave, stats) ->
            val tag = if (wave < warmupWaves) " [warmup, excluded from overall]" else ""
            append("wave $wave$tag: $stats")
        }
        append(traffic)
        val nodeCount = traffic.overall.nodeCount
        val udpRecv = traffic.overall.bytesReceived
        val gossipFraction = if (udpRecv > 0) gossipBytesReceived.toDouble() / udpRecv else 0.0
        appendLine(
            "gossip bytes/node: sent=%.0f recv=%.0f (%d/%d total); gossip/udp ratio: %.1f%%"
                .format(
                    gossipBytesSent.toDouble() / nodeCount,
                    gossipBytesReceived.toDouble() / nodeCount,
                    gossipBytesSent,
                    gossipBytesReceived,
                    gossipFraction * 100
                )
        )
        appendLine(
            "gossip publish bytes/node: sent=%.0f recv=%.0f (%d/%d total)"
                .format(
                    gossipPublishBytesSent.toDouble() / nodeCount,
                    gossipPublishBytesReceived.toDouble() / nodeCount,
                    gossipPublishBytesSent,
                    gossipPublishBytesReceived
                )
        )
        appendLine(
            "gossip control bytes/node: sent=%.0f recv=%.0f (%d/%d total)"
                .format(
                    gossipControlBytesSent.toDouble() / nodeCount,
                    gossipControlBytesReceived.toDouble() / nodeCount,
                    gossipControlBytesSent,
                    gossipControlBytesReceived
                )
        )
        appendLine(
            "gossip duplication ($scope): %.2fx (%d publish msgs recv / %d deliveries); %.0fB per message"
                .format(
                    duplicationFactor,
                    measuredSum(gossipPublishMessagesReceivedByWave),
                    overall.actualDeliveries,
                    gossipBytesPerPublishedMessage
                )
        )
        appendLine(
            "gossip publish bytes/node/wave ($scope): %.0f (%.2f MB)"
                .format(publishBytesReceivedPerNodePerWave, publishBytesReceivedPerNodePerWave / 1e6)
        )
        // Attributed by the wave index in the payload rather than by wall clock, so a wave's bytes
        // stay credited to it even once the next wave has started publishing.
        gossipPublishBytesSentByWave.keys.union(gossipPublishBytesReceivedByWave.keys).sorted()
            .forEach { wave ->
                val sent = gossipPublishBytesSentByWave[wave] ?: 0
                val recv = gossipPublishBytesReceivedByWave[wave] ?: 0
                appendLine(
                    "gossip publish bytes/node wave $wave: sent=%.0f recv=%.0f (%d/%d total)"
                        .format(
                            sent.toDouble() / nodeCount,
                            recv.toDouble() / nodeCount,
                            sent,
                            recv
                        )
                )
            }
        mesh?.let { append(it) }
        // In DcSlotMessageType declaration order rather than config order, so two runs that list
        // their messages differently still report them the same way.
        messages.toSortedMap().values.forEach { append(it) }
        slotTraffic?.let { append(it) }
        append(groups)
    }
}

/**
 * Raw UDP datagram traffic sent/received by every node during a period of the run, e.g. one wave.
 * This is transport-level traffic — including QUIC's own overhead (handshakes, ACKs, retransmits),
 * not just gossip payload bytes — so it reflects what the network actually had to carry, and it is
 * symmetric by construction: every packet a node emits is also recorded as inbound at its peer.
 *
 * Per-node figures are simple averages ([totalPackets] and [totalBytes] each divided by
 * [nodeCount]), not a distribution — a node's role (publisher vs. plain subscriber, high- vs.
 * low-degree) affects its share, but this is meant as a single cost-per-node headline number rather
 * than another set of percentiles.
 */
data class DcTrafficStats(
    val nodeCount: Int,
    val packetsSent: Long,
    val packetsReceived: Long,
    val bytesSent: Long,
    val bytesReceived: Long
) {
    val avgPacketsSentPerNode: Double get() = perNode(packetsSent)
    val avgPacketsReceivedPerNode: Double get() = perNode(packetsReceived)
    val avgBytesSentPerNode: Double get() = perNode(bytesSent)
    val avgBytesReceivedPerNode: Double get() = perNode(bytesReceived)

    private fun perNode(total: Long): Double = if (nodeCount == 0) 0.0 else total.toDouble() / nodeCount

    override fun toString(): String =
        "packets/node: sent=%.1f recv=%.1f (%d/%d total); bytes/node: sent=%.0f recv=%.0f (%d/%d total)"
            .format(
                avgPacketsSentPerNode,
                avgPacketsReceivedPerNode,
                packetsSent,
                packetsReceived,
                avgBytesSentPerNode,
                avgBytesReceivedPerNode,
                bytesSent,
                bytesReceived
            )

    companion object {
        fun of(events: List<DatagramPacketTraceEvent>, nodeCount: Int): DcTrafficStats {
            var packetsSent = 0L
            var packetsReceived = 0L
            var bytesSent = 0L
            var bytesReceived = 0L
            events.forEach { event ->
                when (event.direction) {
                    DatagramPacketTraceEvent.Direction.OUTBOUND -> {
                        packetsSent++
                        bytesSent += event.bytes
                    }
                    DatagramPacketTraceEvent.Direction.INBOUND -> {
                        packetsReceived++
                        bytesReceived += event.bytes
                    }
                }
            }
            return DcTrafficStats(
                nodeCount = nodeCount,
                packetsSent = packetsSent,
                packetsReceived = packetsReceived,
                bytesSent = bytesSent,
                bytesReceived = bytesReceived
            )
        }
    }
}

/**
 * Traffic for the whole run plus a breakdown per wave. [overall] spans the entire run — including
 * mesh formation during [DcAttestationConfig.warmup], before any wave publishes — so it reflects the
 * true bandwidth cost; [perWave] only covers each wave's own time window, for comparing waves to
 * each other.
 */
data class DcTrafficReport(
    val overall: DcTrafficStats,
    val perWave: Map<Int, DcTrafficStats>,
    /**
     * Whole-run traffic of each node group, keyed by [DcNode.groupName] (or [DcGroupStats.UNNAMED]
     * for nodes whose group was not named, whenever at least one group was). Empty when no group in
     * the network was named at all. Groups partition the network, so these add up to [overall].
     */
    val perGroup: Map<String, DcTrafficStats> = emptyMap()
) {
    override fun toString(): String = buildString {
        appendLine("traffic overall: $overall")
        perWave.toSortedMap().forEach { (wave, stats) ->
            appendLine("traffic wave $wave: $stats")
        }
    }

    companion object {
        /**
         * Buckets [events] by wave using [waveTimes]: wave `i` spans from its own publish time up to
         * the next wave's (or [completeAt] for the last wave). Events before the first wave time
         * (mesh formation during warmup) fall into no wave and are only reflected in [overall].
         *
         * [groupNodes] maps each group name to the node ids in it, for the [perGroup] breakdown;
         * leave it empty for no breakdown at all.
         */
        fun of(
            events: List<DatagramPacketTraceEvent>,
            waveTimes: List<Duration>,
            completeAt: Duration,
            nodeCount: Int,
            groupNodes: Map<String, Set<SimNodeId>> = emptyMap()
        ): DcTrafficReport {
            val boundaries = waveTimes + completeAt
            val perWave = waveTimes.indices.associateWith { wave ->
                val start = boundaries[wave]
                val end = boundaries[wave + 1]
                DcTrafficStats.of(events.filter { it.at >= start && it.at < end }, nodeCount)
            }
            // One pass over the events rather than one per group: a heavy run records millions.
            val groupOfNode = groupNodes.entries
                .flatMap { (name, ids) -> ids.map { it to name } }
                .toMap()
            val eventsByGroup = events.groupBy { groupOfNode[it.nodeId] }
            return DcTrafficReport(
                overall = DcTrafficStats.of(events, nodeCount),
                perWave = perWave,
                perGroup = groupNodes.mapValues { (name, ids) ->
                    DcTrafficStats.of(eventsByGroup[name].orEmpty(), ids.size)
                }
            )
        }
    }
}

/**
 * Nearest-rank percentile over an already sorted list: the smallest value at or above the given
 * fraction of the samples. Chosen over interpolation so every reported figure is a latency that
 * actually occurred.
 */
fun List<Duration>.percentile(fraction: Double): Duration? {
    require(fraction in 0.0..1.0) { "fraction must be in [0, 1], got $fraction" }
    if (isEmpty()) return null
    val rank = kotlin.math.ceil(fraction * size).toInt().coerceIn(1, size)
    return this[rank - 1]
}

fun List<Duration>.meanOrNull(): Duration? =
    if (isEmpty()) null else fold(Duration.ZERO) { acc, d -> acc + d } / size
