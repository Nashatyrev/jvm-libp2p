package playground

import io.libp2p.etc.types.millis
import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.DcAnnounceTimeline
import io.libp2p.example.dc.DcAttackOutcome.Percentiles
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.DcNodeProgram
import io.libp2p.example.dc.DcPublisherSelection
import io.libp2p.example.dc.DcRunConfig
import io.libp2p.example.dc.DcScenario
import io.libp2p.example.dc.DcSlotMessageConfig
import io.libp2p.example.dc.DcSlotMessageDelivery
import io.libp2p.example.dc.DcSlotMessageTopics
import io.libp2p.example.dc.DcSlotMessageType
import io.libp2p.example.dc.peerGraph
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.pubsub.gossip.IWantPolicy
import io.libp2p.quicsim.runner.SimulatedQuicScenarioRunner
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The problem disciplined pulls address, in its smallest form: one large message on a slow network.
 *
 * A node whose mesh copies arrive slowly keeps hearing IHAVEs for the message from peers that
 * already have it, and the original follow-up ([IWantPolicy.ALL_ANNOUNCERS]) asks every one of them.
 * Each request is another full copy on the same narrow link, which makes the mesh copies later
 * still, which lets more IHAVEs in. On a fast link the mesh delivers first, nothing is asked for,
 * and copies per node settle near the mesh degree.
 *
 * 100 nodes with 30 peers each on symmetric links, eth ladder D=8/6/12/6 with a 700 ms heartbeat,
 * Gossip v1.2 (IDONTWANT on). A batch of messages per slot on a global topic from one publisher in the
 * fast group, slots far enough apart (60 s) that they never overlap. Optionally [slowCount] of the 100
 * run on a slow link among fast neighbours -- the shape of a home node in a datacentre-heavy network,
 * where the neighbours have the message long before the slow node does and keep announcing it.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*DcIWantAmplificationRunnerTest*" \
 *   -Ddc.bandwidthsMbps=1000 -Ddc.slowCount=10 -Ddc.slowMbps=10 -Ddc.heap=24g -Ddc.directMemory=8g
 * ```
 *
 *  - `dc.bandwidthsMbps`: link rates of the (fast) group, swept (default 10,20,50,100,1000)
 *  - `dc.peers`: peers per node (default 30)
 *  - `dc.timelineDir`: record when each IHAVE/IDONTWANT reaches each node, print the distributions
 *    and write every event to a CSV there
 *  - `dc.slowCount`, `dc.slowMbps`: nodes of the 100 on a slow link (default 0), and its rate (default 10)
 *  - `dc.iwant`: policies swept (default all_announcers,disciplined,none)
 *  - `dc.messageKiB`: message size (default 1024)
 *  - `dc.messagesPerSlot`: messages published at once by one publisher each slot (default 1); latency
 *    is reported per message and for the whole batch -- a node has it once all of them have arrived
 *  - `dc.slots`: batches, one per slot; the first is warm-up and not counted (default 4)
 */
@Tag("simulation")
class DcIWantAmplificationRunnerTest {

    private fun prop(name: String, default: String) = System.getProperty(name) ?: default

    @Test
    fun `iwant amplification on a slow network`() {
        val bandwidths = prop("dc.bandwidthsMbps", "10,20,50,100,1000").split(",").map { it.trim().toLong() }
        val policies = prop("dc.iwant", "all_announcers,disciplined,none").split(",")
            .map { IWantPolicy.valueOf(it.trim().uppercase()) }
        val messageBytes = prop("dc.messageKiB", "1024").toInt() * 1024
        val messagesPerSlot = prop("dc.messagesPerSlot", "1").toInt()
        val slots = prop("dc.slots", "4").toInt()
        val slowCount = prop("dc.slowCount", "0").toInt()
        val slowMbps = prop("dc.slowMbps", "10").toLong()
        val nodeCount = 100
        val peers = prop("dc.peers", "30").toInt()
        // Directory for per-event timeline CSVs; also switches the timeline report on.
        val timelineDir = System.getProperty("dc.timelineDir")?.let { java.io.File(it).apply { mkdirs() } }

        val config = DcRunConfig(
            slotCount = slots,
            // The first message runs on cold QUIC windows: at 1 Gbit/s it took p50 4.6 s against
            // 0.85 s for the third, and drew IWANTs a warm network never sends. Keep it out.
            warmupSlots = 1,
            slotInterval = 60.seconds,
            settle = 60.seconds,
            messages = listOf(
                DcSlotMessageConfig(
                    type = DcSlotMessageType.BLOCK,
                    sizeBytes = messageBytes,
                    publisherGroups = setOf("fast"),
                    publisherSelection = DcPublisherSelection.RANDOM_NODE,
                    messagesPerSlot = messagesPerSlot,
                    topics = DcSlotMessageTopics.Global
                )
            ),
            recordAnnounceTimeline = timelineDir != null,
            randomSeed = 1
        )
        println(
            "IWANT amplification: nodes=$nodeCount (slow=$slowCount at $slowMbps Mbit/s) peers=$peers " +
                "message=$messagesPerSlot x ${messageBytes / 1024} KiB slots=$slots " +
                "bandwidths=$bandwidths Mbit/s policies=$policies"
        )

        val summary = mutableListOf<String>()
        for (mbps in bandwidths) for (policy in policies) {
            val params = GossipParams.builder()
                .D(8).DLow(6).DHigh(12).DLazy(6).heartbeatInterval(700.millis)
                .maxGossipMessageSize(2 * messageBytes)
                .iWantPolicy(policy)
                .build()
            val network = DcNetworkBuilder.world(randomSeed = 1)
                .addGroup(count = nodeCount - slowCount) {
                    name = "fast"
                    spreadOverRegions()
                    bandwidth = Bandwidths.symmetric(Bandwidths.mbitPerSecond(mbps))
                    validators = 1
                    this.peers = peers
                    gossipParams = params
                }
                .also { builder ->
                    if (slowCount > 0) {
                        builder.addGroup(count = slowCount) {
                            name = "slow"
                            spreadOverRegions()
                            bandwidth = Bandwidths.symmetric(Bandwidths.mbitPerSecond(slowMbps))
                            validators = 1
                            this.peers = peers
                            gossipParams = params
                        }
                    }
                }
                .build()
            val graph = network.peerGraph(minPeersPerSubnet = 1, randomSeed = 1)
            val started = System.currentTimeMillis()
            val factory = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = Runtime.getRuntime().availableProcessors()
            ).run(DcScenario.of(network, graph, config)).nodeProgramFactory
            val wallSeconds = (System.currentTimeMillis() - started) / 1000

            val measured = config.measuredSlots.toSet()
            val recorder = factory.messageRecorders.getValue(DcSlotMessageType.BLOCK)
            val allPublished = recorder.published().map { it.message }
            val allDeliveries = recorder.deliveries()
            val publisherOf = allPublished.associate { it.slotIndex to it.publisherNodeId }

            /**
             * Stats over the receivers in [nodes] and the slots in [inSlots]. One batch per slot, so
             * what a node reads or asks for in a slot is about that batch. Copies arriving by IWANT
             * are counted as the IWANT ids the node sent: in an honest network every request is
             * answered, so the two match up to retransmission limits.
             */
            fun stats(nodes: List<DcNodeProgram>, inSlots: Set<Int>): Stats {
                val ids = nodes.map { it.simNodeId }.toSet()
                val nodeSlots = nodes.flatMap { program ->
                    inSlots.filter { publisherOf[it] != program.simNodeId }.map { program to it }
                }
                val deliveries = allDeliveries.filter { it.slotIndex in inSlots && it.receiverNodeId in ids }
                val reads = nodeSlots.map { (p, slot) -> p.gossipByteCounter.publishMessagesReadBySlot[slot] ?: 0L }
                val pulls = nodeSlots.sumOf { (p, slot) -> p.gossipByteCounter.iwantIdsWrittenBySlot[slot] ?: 0L }
                val announced = nodeSlots.sumOf { (p, slot) -> p.gossipByteCounter.ihaveIdsReadBySlot[slot] ?: 0L }
                val bytes = nodeSlots.sumOf { (p, slot) -> p.gossipByteCounter.publishBytesReadBySlot[slot] ?: 0L }
                val sources = nodeSlots.mapNotNull { (p, slot) -> p.copySources?.bySlot()?.get(slot) }
                return Stats(
                    nodes = ids.size,
                    expected = nodeSlots.size * messagesPerSlot,
                    deliveries = deliveries,
                    messagesPerSlot = messagesPerSlot,
                    copies = reads.sum().toDouble() / deliveries.size,
                    pulled = pulls.toDouble() / deliveries.size,
                    copiesPerNodeSlot = reads.map { it.toDouble() / messagesPerSlot }.sorted(),
                    amplification = bytes / (deliveries.size.toDouble() * messageBytes),
                    mesh = nodes.flatMap { it.finalMeshSizes }.average(),
                    fromMesh = sources.sumOf { it.meshCopies }.toDouble() / deliveries.size,
                    fromOutsideMesh = sources.sumOf { it.nonMeshCopies }.toDouble() / deliveries.size,
                    ihaveReceived = announced.toDouble() / deliveries.size,
                    meshAtFirstCopy = sources.sumOf { it.meshSizeAtFirstCopySum }.toDouble() / sources.sumOf { it.messages },
                    distinctMeshSenders = sources.sumOf { it.distinctMeshSendersSum }.toDouble() / sources.sumOf { it.messages }
                )
            }

            val groups = factory.programs.groupBy { network.node(it.simNodeId).groupName!! }.toSortedMap()
            println("=== $mbps Mbit/s policy=${policy.name.lowercase()}  [${wallSeconds}s wall]")
            groups.forEach { (group, nodes) ->
                val s = stats(nodes, measured)
                println("  [$group] $s")
                (0 until slots).forEach { slot ->
                    val tag = if (slot in measured) "" else " (warm-up, not counted)"
                    println("      slot $slot: ${stats(nodes, setOf(slot)).short()}$tag")
                }
                summary += (listOf("$mbps", policy.name.lowercase(), group) + s.csv()).joinToString(",")
                if (timelineDir != null) println(announceTimeline(group, nodes, measured, publisherOf))
            }
            timelineDir?.let { dir ->
                val shape = "${messagesPerSlot}x${messageBytes / 1024}k-p$peers-slow$slowCount"
                val file = java.io.File(dir, "timeline-$shape-${mbps}m-${policy.name.lowercase()}.csv")
                file.printWriter().use { out ->
                    out.println("group,node,slot,message,kind,ms_after_publish,ms_after_first_copy")
                    groups.forEach { (group, nodes) ->
                        nodes.forEach { node ->
                            node.announceTimeline!!.entries().values.forEachIndexed { index, e ->
                                val slot = e.slotIndex ?: return@forEachIndexed
                                val published = e.publishedAt ?: return@forEachIndexed
                                if (slot !in measured || publisherOf[slot] == node.simNodeId) return@forEachIndexed
                                val first = e.firstCopyAt!!
                                fun row(kind: String, t: kotlin.time.Duration) = out.println(
                                    "$group,${node.simNodeId},$slot,$index,$kind," +
                                        "${(t - published).inWholeMilliseconds},${(t - first).inWholeMilliseconds}"
                                )
                                row("first_copy", first)
                                e.ihaveAt.forEach { row("ihave", it) }
                                e.idontwantAt.forEach { row("idontwant", it) }
                                e.iwantSentAt.forEach { row("iwant_sent", it) }
                            }
                        }
                    }
                }
                println("  timeline events -> $file")
            }
        }
        println("\nSUMMARY")
        println("mbps,policy,group,${Stats.CSV_HEADER}")
        summary.forEach(::println)
    }

    /**
     * When IDONTWANTs (from mesh peers) and IHAVEs (from everyone else) for a message reach a node,
     * against the publish time and against each other, over (node, message) pairs of [group].
     */
    private fun announceTimeline(
        group: String,
        nodes: List<DcNodeProgram>,
        measured: Set<Int>,
        publisherOf: Map<Int, io.libp2p.quicsim.sim.SimNodeId>
    ): String {
        val pairs = nodes.flatMap { node ->
            node.announceTimeline!!.entries().values.filter { e ->
                e.slotIndex in measured && e.publishedAt != null && publisherOf[e.slotIndex] != node.simNodeId
            }
        }
        fun ms(values: List<Long>): String {
            if (values.isEmpty()) return "-"
            val sorted = values.sorted()
            fun at(q: Double) = sorted[(q * sorted.size).toInt().coerceAtMost(sorted.size - 1)]
            return "p10=${at(0.1)} p50=${at(0.5)} p90=${at(0.9)} (n=${sorted.size})"
        }
        fun rel(pick: (DcAnnounceTimeline.Entry) -> List<kotlin.time.Duration>) =
            ms(pairs.flatMap { e -> pick(e).map { (it - e.publishedAt!!).inWholeMilliseconds } })
        val bothKinds = pairs.filter { it.ihaveAt.isNotEmpty() && it.idontwantAt.isNotEmpty() }
        val idwFirst = bothKinds.count { it.idontwantAt.minOf { t -> t } < it.ihaveAt.minOf { t -> t } }
        val gaps = bothKinds.map { (it.ihaveAt.minOf { t -> t } - it.idontwantAt.minOf { t -> t }).inWholeMilliseconds }
        val ihaveBeforeCopy = pairs.sumOf { e -> e.ihaveAt.count { it < e.firstCopyAt!! } }
        val ihaveBeforeCopyAfterIdw = pairs.sumOf { e ->
            val firstIdw = e.idontwantAt.minOrNull() ?: return@sumOf 0
            e.ihaveAt.count { it < e.firstCopyAt!! && it > firstIdw }
        }
        val idwBeforeCopy = pairs.sumOf { e -> e.idontwantAt.count { it < e.firstCopyAt!! } }
        val n = pairs.size.toDouble()
        return buildString {
            appendLine("    timeline [$group], ms after publish, over ${pairs.size} (node, message) pairs:")
            appendLine("      first copy          ${rel { listOfNotNull(it.firstCopyAt) }}")
            appendLine("      first IDONTWANT     ${rel { listOfNotNull(it.idontwantAt.minOrNull()) }}")
            appendLine("      all IDONTWANT       ${rel { it.idontwantAt }}")
            appendLine("      first IHAVE         ${rel { listOfNotNull(it.ihaveAt.minOrNull()) }}")
            appendLine("      all IHAVE           ${rel { it.ihaveAt }}")
            appendLine("      IWANT sent          ${rel { it.iwantSentAt }}")
            appendLine(
                "      per message: IDONTWANT %.1f (%.1f before the copy), IHAVE %.1f (%.1f before the copy, %.1f of them after the first IDONTWANT)"
                    .format(
                        pairs.sumOf { it.idontwantAt.size } / n,
                        idwBeforeCopy / n,
                        pairs.sumOf { it.ihaveAt.size } / n,
                        ihaveBeforeCopy / n,
                        ihaveBeforeCopyAfterIdw / n
                    )
            )
            append(
                "      first IDONTWANT before first IHAVE in %.1f%% of %d pairs; first IHAVE - first IDONTWANT: %s"
                    .format(100.0 * idwFirst / bothKinds.size.coerceAtLeast(1), bothKinds.size, ms(gaps))
            )
        }
    }

    private class Stats(
        val nodes: Int,
        val expected: Int,
        val deliveries: List<DcSlotMessageDelivery>,
        val messagesPerSlot: Int,
        val copies: Double,
        val pulled: Double,
        val copiesPerNodeSlot: List<Double>,
        val amplification: Double,
        val mesh: Double,
        val fromMesh: Double,
        val fromOutsideMesh: Double,
        val meshAtFirstCopy: Double,
        val distinctMeshSenders: Double,
        val ihaveReceived: Double
    ) {
        val perMessage = Percentiles.of(deliveries.map { it.latency })

        /** A node has the batch of a slot once its last message has arrived. */
        val batch = Percentiles.of(
            deliveries.groupBy { it.slotIndex to it.receiverNodeId }
                .filterValues { it.size == messagesPerSlot }
                .values.map { got -> got.maxOf { it.latency } }
        )

        fun q(p: Double) = copiesPerNodeSlot[(p * copiesPerNodeSlot.size).toInt().coerceAtMost(copiesPerNodeSlot.size - 1)]

        fun short() = "batch ${batch ?: "-"}  copies/msg=%.2f of which iwant=%.2f; from mesh %.2f outside %.2f, mesh at first copy %.1f"
            .format(copies, pulled, fromMesh, fromOutsideMesh, meshAtFirstCopy)

        override fun toString() =
            "nodes=$nodes delivered ${deliveries.size}/$expected  per message ${perMessage ?: "-"}\n" +
                "      whole batch ${batch ?: "-"}\n" +
                (
                    "      copies/msg: mean=%.2f (mesh %.2f + iwant %.2f) per node-slot p50=%.1f p90=%.1f max=%.1f  " +
                        "publish-bytes amplification=%.2fx  mesh=%.1f"
                    )
                    .format(copies, copies - pulled, pulled, q(0.5), q(0.9), copiesPerNodeSlot.last(), amplification, mesh) +
                (
                    "\n      by sender: from mesh peers %.2f (distinct mesh senders %.2f, mesh size at first copy %.2f), " +
                        "from outside the mesh %.2f; iwant ids sent %.2f, ihave ids received %.2f"
                    )
                    .format(fromMesh, distinctMeshSenders, meshAtFirstCopy, fromOutsideMesh, pulled, ihaveReceived)

        fun csv(): List<Any> = listOf(
            "%.4f".format(deliveries.size.toDouble() / expected),
            perMessage?.p50?.inWholeMilliseconds ?: "-", perMessage?.p99?.inWholeMilliseconds ?: "-",
            batch?.p50?.inWholeMilliseconds ?: "-", batch?.p90?.inWholeMilliseconds ?: "-",
            batch?.p99?.inWholeMilliseconds ?: "-", batch?.max?.inWholeMilliseconds ?: "-",
            "%.2f".format(copies), "%.2f".format(copies - pulled), "%.2f".format(pulled),
            "%.1f".format(q(0.5)), "%.1f".format(q(0.9)), "%.1f".format(copiesPerNodeSlot.last()),
            "%.2f".format(amplification), "%.1f".format(mesh),
            "%.2f".format(fromMesh), "%.2f".format(fromOutsideMesh), "%.2f".format(distinctMeshSenders), "%.2f".format(meshAtFirstCopy),
            "%.2f".format(ihaveReceived)
        )

        companion object {
            const val CSV_HEADER = "delivered,msg_p50_ms,msg_p99_ms,batch_p50_ms,batch_p90_ms,batch_p99_ms,batch_max_ms," +
                "copies_mean,copies_mesh,copies_iwant,copies_p50,copies_p90,copies_max,byte_amplification,mesh," +
                "from_mesh,from_outside_mesh,distinct_mesh_senders,mesh_at_first_copy,ihave_received"
        }
    }
}
