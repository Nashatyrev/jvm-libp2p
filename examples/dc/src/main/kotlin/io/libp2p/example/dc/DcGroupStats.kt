package io.libp2p.example.dc

import io.libp2p.quicsim.sim.SimNodeId

/**
 * What one node group carried and what it saw, for a run whose groups were named.
 *
 * Groups are where a run's populations differ — link rate, peer count, validators hosted — so the
 * whole-network averages in [DcAttestationReport] hide exactly the thing worth knowing: whether the
 * residential nodes are saturated while the datacenter ones idle, and whether that shows up as
 * later deliveries for that group. Everything here is per group, and the byte figures are per node
 * of the group so groups of different sizes can be compared directly.
 *
 * [messagesReceived] carries one [DcDeliveryStats] per configured [DcSlotMessageType] — block,
 * payload, blob column, FFG attestation, or a custom type — counted at the *receiving* node, so it
 * reports what this group observed rather than what it published.
 */
data class DcGroupStats(
    val groupName: String,
    val nodeCount: Int,
    val validatorCount: Int,
    /** Distinct access links in the group, e.g. `50 Mbit/s` or `50 Mbit/s down/25 Mbit/s up`. */
    val links: List<String>,
    /** Raw UDP traffic of the group's nodes over the whole run, including QUIC's own overhead. */
    val traffic: DcTrafficStats,
    val gossipBytesSent: Long,
    val gossipBytesReceived: Long,
    val gossipPublishBytesSent: Long,
    val gossipPublishBytesReceived: Long,
    val messagesReceived: Map<DcSlotMessageType, DcDeliveryStats>,
    val mesh: DcMeshStats?
) {
    val gossipControlBytesSent: Long get() = gossipBytesSent - gossipPublishBytesSent
    val gossipControlBytesReceived: Long get() = gossipBytesReceived - gossipPublishBytesReceived

    val validatorsPerNode: Double get() = if (nodeCount == 0) 0.0 else validatorCount.toDouble() / nodeCount

    private fun perNode(total: Long): Double = if (nodeCount == 0) 0.0 else total.toDouble() / nodeCount

    override fun toString(): String = buildString {
        appendLine(
            "group '%s': nodes=%d validators=%d (%.1f/node) links=%s".format(
                groupName,
                nodeCount,
                validatorCount,
                validatorsPerNode,
                links.joinToString()
            )
        )
        appendLine(
            "  udp bytes/node: sent=%.0f recv=%.0f; packets/node: sent=%.1f recv=%.1f".format(
                traffic.avgBytesSentPerNode,
                traffic.avgBytesReceivedPerNode,
                traffic.avgPacketsSentPerNode,
                traffic.avgPacketsReceivedPerNode
            )
        )
        appendLine(
            "  gossip publish bytes/node: sent=%.0f recv=%.0f; control bytes/node: sent=%.0f recv=%.0f"
                .format(
                    perNode(gossipPublishBytesSent),
                    perNode(gossipPublishBytesReceived),
                    perNode(gossipControlBytesSent),
                    perNode(gossipControlBytesReceived)
                )
        )
        messagesReceived.toSortedMap(compareBy { it.id }).forEach { (type, stats) ->
            append("  received $type: $stats".trimEnd().replace("\n", "\n  "))
            appendLine()
        }
        mesh?.let { append("  " + it.toString().trimEnd().replace("\n", "\n  ") + "\n") }
    }

    companion object {
        /** Group name used for nodes whose group was left unnamed, so the parts still add up. */
        const val UNNAMED: String = "(unnamed)"
    }
}

/**
 * Per-group breakdown of a run. Empty when nothing was named — see [DcNodeGroup.name].
 *
 * Nodes from unnamed groups are gathered under [DcGroupStats.UNNAMED] whenever any group in the
 * network was named, so the groups here always partition the network and their totals reconcile
 * with the whole-run figures; when nothing at all was named there is nothing to break down.
 */
data class DcGroupReport(
    val groups: Map<String, DcGroupStats>
) {
    val groupNames: Set<String> get() = groups.keys

    operator fun get(groupName: String): DcGroupStats? = groups[groupName]

    override fun toString(): String = buildString {
        if (groups.isEmpty()) return@buildString
        appendLine("--- per group ---")
        groups.values.forEach { append(it) }
    }

    companion object {
        /**
         * Assembles the breakdown from the whole-run material already collected elsewhere: the
         * population, per-group UDP traffic, and the per-node gossip counters, mesh sizes and
         * message deliveries.
         *
         * Expected delivery counts come from the population rather than from what arrived, which is
         * the whole point of reporting a ratio. [subscribersOf] gives the subscriber population of
         * one message's topic — everything a caller already needs to compute a whole-network
         * expected count — grouped here instead so both add up to the same total by construction.
         */
        fun <R> of(
            network: DcNetwork<R>,
            trafficPerGroup: Map<String, DcTrafficStats>,
            gossipCounters: Map<SimNodeId, GossipByteCounter>,
            meshSizes: Map<SimNodeId, List<Int>>,
            messagesByType: Map<DcSlotMessageType, Pair<List<DcSlotMessagePublication>, List<DcSlotMessageDelivery>>>,
            subscribersOf: (DcSlotMessage) -> List<DcNode<R>>,
            warmupWaves: Int = 0
        ): DcGroupReport {
            val nodesByGroup = network.nodes.groupBy { it.groupName ?: DcGroupStats.UNNAMED }
            if (nodesByGroup.keys == setOf(DcGroupStats.UNNAMED)) return DcGroupReport(emptyMap())

            val groupOfNode = network.nodes.associate { it.simNodeId to (it.groupName ?: DcGroupStats.UNNAMED) }

            // Subscriber population is static per (type, subnet) topic, so it is computed once per
            // topic rather than once per message — a run can publish far more messages than topics.
            val subscriberGroupCounts = mutableMapOf<Pair<DcSlotMessageType, Int?>, Map<String, Int>>()
            val subscriberNodeIds = mutableMapOf<Pair<DcSlotMessageType, Int?>, Set<SimNodeId>>()
            fun topicKeyOf(message: DcSlotMessage) = message.type to message.subnetId
            fun subscribersOfCached(message: DcSlotMessage): Pair<Map<String, Int>, Set<SimNodeId>> {
                val key = topicKeyOf(message)
                val counts = subscriberGroupCounts.getOrPut(key) {
                    subscribersOf(message).groupingBy { it.groupName ?: DcGroupStats.UNNAMED }.eachCount()
                }
                val ids = subscriberNodeIds.getOrPut(key) {
                    subscribersOf(message).mapTo(hashSetOf()) { it.simNodeId }
                }
                return counts to ids
            }

            val messagesReceivedByGroup = nodesByGroup.keys.associateWith { mutableMapOf<DcSlotMessageType, MutableList<kotlin.time.Duration>>() }
            val publishedCountByGroupAndType = nodesByGroup.keys.associateWith { mutableMapOf<DcSlotMessageType, Int>() }
            val expectedByGroupAndType = nodesByGroup.keys.associateWith { mutableMapOf<DcSlotMessageType, Int>() }

            messagesByType.forEach { (type, publishedAndDelivered) ->
                val (published, deliveries) = publishedAndDelivered
                val measuredPublished = published.filter { it.message.slotIndex >= warmupWaves }
                val measuredDeliveries = deliveries.filter { it.slotIndex >= warmupWaves }

                measuredPublished.forEach { publication ->
                    val (counts, ids) = subscribersOfCached(publication.message)
                    val publisherGroup = groupOfNode.getValue(publication.message.publisherNodeId)
                    val publisherCounted = publication.message.publisherNodeId in ids
                    counts.forEach { (group, count) ->
                        val expected = count - if (publisherCounted && group == publisherGroup) 1 else 0
                        val byType = expectedByGroupAndType.getValue(group)
                        byType[type] = (byType[type] ?: 0) + expected
                    }
                    val byType = publishedCountByGroupAndType.getValue(publisherGroup)
                    byType[type] = (byType[type] ?: 0) + 1
                }
                measuredDeliveries.forEach { delivery ->
                    val group = groupOfNode.getValue(delivery.receiverNodeId)
                    messagesReceivedByGroup.getValue(group)
                        .getOrPut(type) { mutableListOf() } += delivery.latency
                }
            }

            val groups = nodesByGroup.mapValues { (groupName, groupNodes) ->
                val nodeIds = groupNodes.map { it.simNodeId }
                DcGroupStats(
                    groupName = groupName,
                    nodeCount = groupNodes.size,
                    validatorCount = groupNodes.sumOf { it.validatorCount },
                    links = groupNodes.map { linkOf(it) }.distinct(),
                    traffic = trafficPerGroup[groupName]
                        ?: DcTrafficStats(groupNodes.size, 0, 0, 0, 0),
                    gossipBytesSent = nodeIds.sumOf { gossipCounters[it]?.bytesWritten ?: 0 },
                    gossipBytesReceived = nodeIds.sumOf { gossipCounters[it]?.bytesRead ?: 0 },
                    gossipPublishBytesSent = nodeIds.sumOf { gossipCounters[it]?.publishBytesWritten ?: 0 },
                    gossipPublishBytesReceived = nodeIds.sumOf { gossipCounters[it]?.publishBytesRead ?: 0 },
                    messagesReceived = messagesByType.keys.associateWith { type ->
                        DcDeliveryStats.of(
                            publishedCount = publishedCountByGroupAndType.getValue(groupName)[type] ?: 0,
                            latencies = messagesReceivedByGroup.getValue(groupName)[type].orEmpty(),
                            expectedDeliveries = expectedByGroupAndType.getValue(groupName)[type] ?: 0,
                            what = type.id
                        )
                    },
                    mesh = nodeIds.mapNotNull { meshSizes[it] }
                        .takeIf { it.isNotEmpty() }
                        ?.let { DcMeshStats.of(it) }
                )
            }
            return DcGroupReport(groups)
        }

        private fun <R> linkOf(node: DcNode<R>): String =
            if (node.hasAsymmetricLink) {
                "${node.bandwidth} down/${node.uploadBandwidth} up"
            } else {
                node.bandwidth.toString()
            }
    }
}
