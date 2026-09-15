package io.libp2p.example.dc

import io.libp2p.quicsim.runner.DatagramPacketTraceEvent
import io.libp2p.quicsim.runner.DatagramTotals
import io.libp2p.quicsim.runner.DatagramTrafficAggregate
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.time.Duration

/**
 * Delivery latency of one set of published messages.
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
    val p90: Duration?,
    val p95: Duration?,
    val p99: Duration?,
    val min: Duration?,
    val max: Duration?,
    val mean: Duration?,
    /** What was published, for the first line of [toString]; the maths is the same either way. */
    val what: String = "messages"
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
            "latency: p50=${p50.ms()} p90=${p90.ms()} p95=${p95.ms()} p99=${p99.ms()} " +
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
            what: String = "messages"
        ): DcDeliveryStats {
            val sorted = latencies.sorted()
            return DcDeliveryStats(
                publishedCount = publishedCount,
                expectedDeliveries = expectedDeliveries,
                actualDeliveries = sorted.size,
                p50 = sorted.percentile(0.50),
                p90 = sorted.percentile(0.90),
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
    val warmupSlots: Int = 0,
    /**
     * Every offset into the slot this type is issued at, in order — one entry per
     * [DcSlotMessageConfig] of this type, so a single-wave type has just
     * [DcSlotMessageConfig.publishOffset]. [config] is the first of those configs; the rest differ
     * only in their offset.
     */
    val publishOffsets: List<Duration> = listOf(config.publishOffset),
    /**
     * Stats per wave within the slot, keyed by [DcSlotMessage.waveIndex], for a type issued as
     * several waves — so a wave that lands while the slot is already busy can be told apart from one
     * that has the network to itself. Holds a single entry for a single-wave type, where it says the
     * same thing as [overall].
     */
    val perWave: Map<Int, DcDeliveryStats> = emptyMap()
) {
    val measuredSlots: List<Int> get() = perSlot.keys.filter { it >= warmupSlots }.sorted()

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
        if (perWave.size > 1) {
            perWave.toSortedMap().forEach { (wave, stats) ->
                val at = publishOffsets.getOrNull(wave)?.let { " @$it" }.orEmpty()
                append("$type wave $wave of ${perWave.size} in slot$at: $stats")
            }
        }
        perSlot.toSortedMap().forEach { (slot, stats) ->
            val tag = if (slot < warmupSlots) " [warmup, excluded from overall]" else ""
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
            warmupSlots: Int = 0,
            publishOffsets: List<Duration> = listOf(config.publishOffset)
        ): DcSlotMessageReport {
            val deliveriesBySlot = deliveries.groupBy { it.slotIndex }
            val publicationsBySlot = published.groupBy { it.message.slotIndex }
            val measuredPublished = published.filter { it.message.slotIndex >= warmupSlots }
            val measuredDeliveries = deliveries.filter { it.slotIndex >= warmupSlots }
            // Deliveries carry only the message id, so the wave they belong to is looked up from the
            // publisher side -- sound because ids are unique across a type's waves, see
            // DcSlotMessageSchedule.create's firstMessageId.
            val waveOfMessageId = measuredPublished.associate { it.message.id to it.message.waveIndex }
            val measuredByWave = measuredPublished.groupBy { it.message.waveIndex }
            val deliveriesByWave = measuredDeliveries.groupBy { waveOfMessageId[it.messageId] }
            return DcSlotMessageReport(
                type = config.type,
                overall = DcDeliveryStats.of(
                    publishedCount = measuredPublished.size,
                    latencies = measuredDeliveries.map { it.latency },
                    expectedDeliveries = measuredPublished.sumOf { expectedDeliveriesOf(it.message) },
                    what = config.type.id
                ),
                perSlot = publicationsBySlot.mapValues { (slot, slotPublications) ->
                    DcDeliveryStats.of(
                        publishedCount = slotPublications.size,
                        latencies = deliveriesBySlot[slot].orEmpty().map { it.latency },
                        expectedDeliveries = slotPublications.sumOf { expectedDeliveriesOf(it.message) },
                        what = config.type.id
                    )
                },
                config = config,
                publishTimes = publicationsBySlot.mapValues { (_, values) ->
                    values.map { it.publishedAt }
                },
                publishers = publicationsBySlot.mapValues { (_, values) ->
                    values.mapTo(linkedSetOf()) { it.message.publisherNodeId }
                },
                warmupSlots = warmupSlots,
                publishOffsets = publishOffsets,
                perWave = measuredByWave.mapValues { (wave, wavePublications) ->
                    DcDeliveryStats.of(
                        publishedCount = wavePublications.size,
                        latencies = deliveriesByWave[wave].orEmpty().map { it.latency },
                        expectedDeliveries = wavePublications.sumOf { expectedDeliveriesOf(it.message) },
                        what = config.type.id
                    )
                }
            )
        }
    }
}

/** Stats for the whole run plus a breakdown per slot, so a slow slot does not hide in the average. */
data class DcRunReport(
    val overall: DcDeliveryStats,
    val perSlot: Map<Int, DcDeliveryStats>,
    val traffic: DcTrafficReport,
    val gossipBytesSent: Long = 0,
    val gossipBytesReceived: Long = 0,
    val gossipPublishBytesSent: Long = 0,
    val gossipPublishBytesReceived: Long = 0,
    val gossipPublishBytesSentBySlot: Map<Int, Long> = emptyMap(),
    val gossipPublishBytesReceivedBySlot: Map<Int, Long> = emptyMap(),
    val gossipPublishMessagesSent: Long = 0,
    val gossipPublishMessagesReceived: Long = 0,
    val gossipPublishMessagesSentBySlot: Map<Int, Long> = emptyMap(),
    val gossipPublishMessagesReceivedBySlot: Map<Int, Long> = emptyMap(),
    /** Leading slots excluded from [overall]; see [DcRunConfig.warmupSlots]. */
    val warmupSlots: Int = 0,
    val mesh: DcMeshStats? = null,
    /**
     * Separate reports keyed by [DcSlotMessageConfig.type]. Block issuance ([DcRunConfig
     * .blocks]) shows up here too, at [DcSlotMessageType.BLOCK] -- it is folded into the same
     * generic message pipeline as every other type, so there is no separate block-shaped report.
     */
    val messages: Map<DcSlotMessageType, DcSlotMessageReport> = emptyMap(),
    /** Per-group breakdown; empty unless a group in the network was named. See [DcNodeGroup.name]. */
    val groups: DcGroupReport = DcGroupReport(emptyMap()),
    /** Where in the slot each message type's bytes land; see [DcRunConfig.slotTrafficBucketDuration]. */
    val slotTraffic: DcSlotTrafficProfile? = null
) {
    val gossipControlBytesSent: Long get() = gossipBytesSent - gossipPublishBytesSent
    val gossipControlBytesReceived: Long get() = gossipBytesReceived - gossipPublishBytesReceived

    /** Slot indices behind [overall], i.e. every slot except the warm-up ones. */
    val measuredSlots: List<Int> get() = perSlot.keys.filter { it >= warmupSlots }.sorted()

    private fun measuredSum(bySlot: Map<Int, Long>): Long =
        bySlot.entries.filter { it.key >= warmupSlots }.sumOf { it.value }

    /** Publish bytes for the measured slots only, so they line up with [overall]. */
    val measuredPublishBytesReceived: Long get() = measuredSum(gossipPublishBytesReceivedBySlot)
    val measuredPublishBytesSent: Long get() = measuredSum(gossipPublishBytesSentBySlot)

    /**
     * Publish bytes one node receives for one slot. Unlike a whole-run total this does not move
     * when [DcRunConfig.slotCount] or [DcRunConfig.warmupSlots] change, so it is
     * the load figure to compare across runs.
     */
    val publishBytesReceivedPerNodePerSlot: Double
        get() {
            val slots = measuredSlots.size
            val nodes = traffic.overall.nodeCount
            return if (slots == 0 || nodes == 0) {
                0.0
            } else {
                measuredPublishBytesReceived.toDouble() / slots / nodes
            }
        }

    /**
     * Copies of each message a node receives, per copy it actually needed. 1.0 would mean every
     * message arrived exactly once; gossipsub pays about one copy per mesh peer, since every mesh
     * peer forwards a new message before learning the recipient already has it.
     *
     * Measured rather than inferred: both terms are counts, so no per-message size is assumed.
     * Restricted to the measured slots so numerator and denominator cover the same slots.
     */
    val duplicationFactor: Double
        get() = if (overall.actualDeliveries == 0) {
            0.0
        } else {
            measuredSum(gossipPublishMessagesReceivedBySlot).toDouble() / overall.actualDeliveries
        }

    /** Mean wire size of one published message, including gossip framing. */
    val gossipBytesPerPublishedMessage: Double
        get() {
            val msgs = measuredSum(gossipPublishMessagesReceivedBySlot)
            return if (msgs == 0L) 0.0 else measuredPublishBytesReceived.toDouble() / msgs
        }

    override fun toString(): String = buildString {
        val scope =
            if (warmupSlots == 0) "all slots" else "slots ${measuredSlots.firstOrNull()}-${measuredSlots.lastOrNull()}"
        append("overall ($scope): $overall")
        perSlot.toSortedMap().forEach { (slot, stats) ->
            val tag = if (slot < warmupSlots) " [warmup, excluded from overall]" else ""
            append("slot $slot$tag: $stats")
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
                    measuredSum(gossipPublishMessagesReceivedBySlot),
                    overall.actualDeliveries,
                    gossipBytesPerPublishedMessage
                )
        )
        appendLine(
            "gossip publish bytes/node/slot ($scope): %.0f (%.2f MB)"
                .format(publishBytesReceivedPerNodePerSlot, publishBytesReceivedPerNodePerSlot / 1e6)
        )
        // Attributed by the slot index in the payload rather than by wall clock, so a slot's bytes
        // stay credited to it even once the next slot has started publishing.
        gossipPublishBytesSentBySlot.keys.union(gossipPublishBytesReceivedBySlot.keys).sorted()
            .forEach { slot ->
                val sent = gossipPublishBytesSentBySlot[slot] ?: 0
                val recv = gossipPublishBytesReceivedBySlot[slot] ?: 0
                appendLine(
                    "gossip publish bytes/node slot $slot: sent=%.0f recv=%.0f (%d/%d total)"
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
 * Raw UDP datagram traffic sent/received by every node during a period of the run, e.g. one slot.
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
        /** From a [DatagramTrafficAggregate] slice; the production path, which never holds events. */
        fun of(totals: DatagramTotals, nodeCount: Int): DcTrafficStats = DcTrafficStats(
            nodeCount = nodeCount,
            packetsSent = totals.packetsSent,
            packetsReceived = totals.packetsReceived,
            bytesSent = totals.bytesSent,
            bytesReceived = totals.bytesReceived
        )

        /** From retained events. Convenient for small unit tests; see the class doc for why runs do not. */
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
 * Traffic for the whole run plus a breakdown per slot. [overall] spans the entire run — including
 * mesh formation during [DcRunConfig.warmup], before any slot publishes — so it reflects the
 * true bandwidth cost; [perSlot] only covers each slot's own time window, for comparing slots to
 * each other.
 */
data class DcTrafficReport(
    val overall: DcTrafficStats,
    val perSlot: Map<Int, DcTrafficStats>,
    /**
     * Whole-run traffic of each node group, keyed by [DcNode.groupName] (or [DcGroupStats.UNNAMED]
     * for nodes whose group was not named, whenever at least one group was). Empty when no group in
     * the network was named at all. Groups partition the network, so these add up to [overall].
     */
    val perGroup: Map<String, DcTrafficStats> = emptyMap()
) {
    override fun toString(): String = buildString {
        appendLine("traffic overall: $overall")
        perSlot.toSortedMap().forEach { (slot, stats) ->
            appendLine("traffic slot $slot: $stats")
        }
    }

    companion object {
        /**
         * Buckets [events] by slot using [slotTimes]: slot `i` spans from its own publish time up to
         * the next slot's (or [completeAt] for the last slot). Events before the first slot time
         * (mesh formation during warmup) fall into no slot and are only reflected in [overall].
         *
         * [groupNodes] maps each group name to the node ids in it, for the [perGroup] breakdown;
         * leave it empty for no breakdown at all.
         */
        /**
         * Same breakdown from a [DatagramTrafficAggregate] instead of retained events — the path a
         * real run takes, since the events themselves do not fit in memory at scale. Slot windows
         * are resolved to the aggregate's own bucket edges, so its resolution should divide the slot
         * interval; the DC scenarios give it [DcRunConfig.slotTrafficBucketDuration], which does.
         */
        fun of(
            traffic: DatagramTrafficAggregate,
            slotTimes: List<Duration>,
            completeAt: Duration,
            nodeCount: Int,
            groupNodes: Map<String, Set<SimNodeId>> = emptyMap()
        ): DcTrafficReport {
            val boundaries = slotTimes + completeAt
            return DcTrafficReport(
                overall = DcTrafficStats.of(traffic.totals(), nodeCount),
                perSlot = slotTimes.indices.associateWith { slot ->
                    DcTrafficStats.of(
                        traffic.totals(from = boundaries[slot], until = boundaries[slot + 1]),
                        nodeCount
                    )
                },
                perGroup = groupNodes.mapValues { (_, ids) ->
                    DcTrafficStats.of(traffic.totals(nodeIds = ids), ids.size)
                }
            )
        }

        fun of(
            events: List<DatagramPacketTraceEvent>,
            slotTimes: List<Duration>,
            completeAt: Duration,
            nodeCount: Int,
            groupNodes: Map<String, Set<SimNodeId>> = emptyMap()
        ): DcTrafficReport {
            val boundaries = slotTimes + completeAt
            val perSlot = slotTimes.indices.associateWith { slot ->
                val start = boundaries[slot]
                val end = boundaries[slot + 1]
                DcTrafficStats.of(events.filter { it.at >= start && it.at < end }, nodeCount)
            }
            // One pass over the events rather than one per group: a heavy run records millions.
            val groupOfNode = groupNodes.entries
                .flatMap { (name, ids) -> ids.map { it to name } }
                .toMap()
            val eventsByGroup = events.groupBy { groupOfNode[it.nodeId] }
            return DcTrafficReport(
                overall = DcTrafficStats.of(events, nodeCount),
                perSlot = perSlot,
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
