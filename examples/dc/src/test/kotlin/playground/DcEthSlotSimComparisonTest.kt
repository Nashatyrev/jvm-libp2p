package playground

import io.libp2p.etc.types.millis
import io.libp2p.example.dc.Bandwidths.mbitPerSecond
import io.libp2p.example.dc.DcDatagramSizes
import io.libp2p.example.dc.DcLink
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.DcPeerGraph
import io.libp2p.example.dc.DcRunConfig
import io.libp2p.example.dc.DcScenario
import io.libp2p.example.dc.DcSlotMessage
import io.libp2p.example.dc.DcSlotMessageConfig
import io.libp2p.example.dc.DcSlotMessageSchedule
import io.libp2p.example.dc.DcSlotMessageTopics
import io.libp2p.example.dc.DcSlotMessageType
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.runner.BandwidthQueueDiscipline
import io.libp2p.quicsim.scenario.QuicNetworkHost
import io.libp2p.quicsim.scenario.QuicNetworkLink
import io.libp2p.quicsim.scenario.QuicNetworkRouter
import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Replays an eth-slot-sim (github.com/sukunrt/eth-slot-sim) run on this simulator, so the two can
 * be compared message for message. The run dir is what `simctl run --dry-run` writes for
 * `configs/dc_compare_n100.yaml`; its `topology.json` and `schedule.json` supply everything that
 * would otherwise be drawn here:
 *
 * - the peer graph, and each edge's one-way latency: every host gets a router of its own, with the
 *   host's up/down bandwidth on the access link and one core link per peer edge. Routing is
 *   shortest-path, as in Shadow with `use_shortest_path`;
 * - which nodes are supernodes (1024/1024 Mbit) and which are home nodes (25 up / 50 down);
 * - column custody, the slot proposers, and who casts each finality vote and aggregate.
 *
 * Per slot, as eth-slot-sim's decoupled + ePBS mode publishes them: an 8 KiB consensus block at
 * 0 s, then from the same proposer a 1 MiB payload and 128 data columns at 1 s; the finality votes
 * of this slot's round (one subnet, every node on it) at 1 s; the round's aggregates at 67 % of the
 * slot. Gossip params follow eth-slot-sim's node.go.
 *
 * Writes every delivery to `<run dir>/jvm_arrivals.csv` in eth-slot-sim's simnet CSV columns and
 * kind numbers, so one script analyses both.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*DcEthSlotSimComparisonTest*" -Ddc.heap=24g \
 *     -Ddc.ethSlotSimRun=/path/to/eth-slot-sim/runs/dc_compare/run-...
 * ```
 */
@Tag("simulation")
class DcEthSlotSimComparisonTest {

    @Test
    fun `eth-slot-sim comparison`() {
        val runDir = File(
            System.getProperty("dc.ethSlotSimRun")
                ?: error("set -Ddc.ethSlotSimRun to an eth-slot-sim run dir")
        )
        val topology = readJson(File(runDir, "topology.json"))
        val schedule = readJson(File(runDir, "schedule.json"))
        val params = schedule["params"] as Map<*, *>
        val config = readJson(File(runDir, "config.yaml"))

        val nodeCount = (params["n"] as Number).toInt()
        val validatorCount = (params["v"] as Number).toInt()
        val roundsPerFinalitySlot = (params["ac_slots_per_finality_slot"] as Number).toInt()
        val slotCount = (params["num_slots"] as Number).toInt()
        val epbs = config["epbs"] as Map<*, *>
        val decoupled = config["decoupled_consensus"] as Map<*, *>
        val slotDuration = (config["slot_duration_seconds"] as Number).toInt().seconds
        val payloadOffset = (epbs["payload_offset_ms"] as Number).toInt().milliseconds
        val voteOffset = (decoupled["fc_vote_offset_ms"] as Number).toInt().milliseconds
        val aggregateOffset = slotDuration * (decoupled["round_aggregation_fraction"] as Number).toInt() / 100

        // Sizes as eth-slot-sim's validator package builds them; the column one assumes Blobs = 21.
        val consensusBlockSize = (epbs["consensus_block_size"] as Number).toInt()
        val payloadSize = (config["block_size"] as Number).toInt()
        val columnSize = 21 * 2144 + 356
        val finalityVoteSize = 226
        val validatorsPerRound = (schedule["validators_per_round_subnet"] as List<*>)
            .map { ((it as List<*>).single() as Number).toInt() }
        val aggregateSize = 328 + (validatorsPerRound.average().toInt() + 7) / 8

        val nodes = (topology["nodes"] as List<*>).map { it as Map<*, *> }.sortedBy { (it["num"] as Number).toInt() }
        require(nodes.size == nodeCount) { "topology has ${nodes.size} nodes, schedule $nodeCount" }
        val columnSubscribers = (schedule["column_subscribers"] as List<*>).map { subs ->
            (subs as List<*>).map { (it as Number).toInt() }
        }
        val custodyOf = (0 until nodeCount).map { node ->
            columnSubscribers.indices.filterTo(sortedSetOf()) { node in columnSubscribers[it] }
        }
        require((schedule["finality_subscribers"] as List<*>).size == 1) { "expects fs_subnets: 1" }
        // Uniform validator mapping: validator v runs on node v % N.
        val validatorsOf = List(nodeCount) { node -> (0 until validatorCount).count { it % nodeCount == node } }

        val gossipParams = GossipParams.builder()
            .D(8).DLow(6).DHigh(12).DLazy(6).DOut(1)
            .heartbeatInterval(700.millis)
            .gossipHistoryLength(6)
            .gossipSize(3)
            // go-libp2p-pubsub's IDONTWANT threshold and eth-slot-sim's GOSSIP_MAX_SIZE
            .iDontWantMinMessageSizeThreshold(1024)
            .maxGossipMessageSize(10 * 1024 * 1024)
            .build()

        val builder = DcNetworkBuilder.world(
            subnetCounts = mapOf(
                DcSlotMessageType.BLOB_COLUMN to columnSubscribers.size,
                DcSlotMessageType.FFG_ATTESTATION to 1
            )
        ).defaults {
            this.gossipParams = gossipParams
            // Regions only label the report: the latencies come from the topology below.
            region = ContinentRegion.EUROPE
        }
        nodes.forEachIndexed { index, node ->
            val up = (node["upload_bw_mbps"] as Number).toLong()
            val down = (node["download_bw_mbps"] as Number).toLong()
            builder.addNode {
                name = if (up >= 1024) "supernode" else "home"
                bandwidth = DcLink(download = mbitPerSecond(down), upload = mbitPerSecond(up))
                validators = validatorsOf[index]
                peers = nodeCount - 1
                messageSubnets(DcSlotMessageType.BLOB_COLUMN, custodyOf[index])
                messageSubnets(DcSlotMessageType.FFG_ATTESTATION, setOf(0))
            }
        }
        val edges = (topology["edges"] as List<*>).map { it as Map<*, *> }.map {
            Triple((it["source"] as Number).toInt(), (it["target"] as Number).toInt(), (it["latency_ms"] as Number).toInt())
        }
        val built = builder.build()
        val network = built.copy(topology = hostRouterTopology(built.nodes.map { it.id }, nodes, edges))
        val adjacency = List(nodeCount) { mutableSetOf<Int>() }
        edges.forEach { (a, b, _) ->
            adjacency[a] += b
            adjacency[b] += a
        }
        val graph = DcPeerGraph(network, adjacency, minPeersPerSubnet = 0)
        println(network.summary())
        println(
            "peer graph: edges=${graph.edgeCount} degree min/avg/max=" +
                "${adjacency.minOf { it.size }}/${adjacency.sumOf { it.size } / nodeCount}/${adjacency.maxOf { it.size }}"
        )

        val blockConfig = DcSlotMessageConfig(DcSlotMessageType.BLOCK, consensusBlockSize, Duration.ZERO)
        val payloadConfig = DcSlotMessageConfig(DcSlotMessageType.PAYLOAD, payloadSize, payloadOffset)
        val columnConfig = DcSlotMessageConfig(
            DcSlotMessageType.BLOB_COLUMN,
            columnSize,
            payloadOffset,
            messagesPerSlot = columnSubscribers.size,
            topics = DcSlotMessageTopics.Subnets(columnSubscribers.size)
        )
        val voteConfig = DcSlotMessageConfig(
            DcSlotMessageType.FFG_ATTESTATION,
            finalityVoteSize,
            voteOffset,
            messagesPerSlot = validatorCount / roundsPerFinalitySlot,
            topics = DcSlotMessageTopics.Subnets(1)
        )
        val aggregateConfig = DcSlotMessageConfig(
            DcSlotMessageType.AGGREGATES,
            aggregateSize,
            aggregateOffset,
            messagesPerSlot = 16
        )
        val runConfig = DcRunConfig(
            slotCount = slotCount,
            warmupSlots = 1,
            slotInterval = slotDuration,
            messages = listOf(blockConfig, payloadConfig, columnConfig, voteConfig, aggregateConfig)
        )

        val slots = schedule["slots"] as List<*>
        val finalityRoundOf = (schedule["finality_round_of"] as List<*>).map { (it as Number).toInt() }
        var nextId = 0
        fun schedule(config: DcSlotMessageConfig, messagesIn: (slot: Int) -> List<Pair<Int, Int?>>) =
            DcSlotMessageSchedule(
                runConfig.slotTimes,
                config,
                (0 until slotCount).flatMap { slot ->
                    messagesIn(slot).mapIndexed { indexInSlot, (publisher, subnet) ->
                        DcSlotMessage(nextId++, slot, indexInSlot, publisher, subnet, config.type)
                    }
                }
            )
        val proposerOf = slots.map { ((it as Map<*, *>)["proposer"] as Number).toInt() }
        val schedules = listOf(
            schedule(blockConfig) { listOf(proposerOf[it] to null) },
            schedule(payloadConfig) { listOf(proposerOf[it] to null) },
            schedule(columnConfig) { slot -> columnSubscribers.indices.map { proposerOf[slot] to it } },
            schedule(voteConfig) { slot ->
                finalityRoundOf.indices
                    .filter { finalityRoundOf[it] == slot % roundsPerFinalitySlot }
                    .map { validator -> validator % nodeCount to 0 }
            },
            schedule(aggregateConfig) { slot ->
                val aggregators = (slots[slot] as Map<*, *>)["finality_aggregators"] as List<*>
                aggregators.flatMap { subnet ->
                    (subnet as List<*>).map { ((it as Map<*, *>)["node"] as Number).toInt() to null }
                }
            }
        )
        schedules.forEach { println("${it.config.type}: ${it.messages.size} messages over $slotCount slots") }

        // -Ddc.queue=CODEL|FQ_CODEL|FIFO picks the link queue; its outputs then carry the name, so
        // runs with different queues sit side by side in the run dir.
        val queue = System.getProperty("dc.queue")?.let { BandwidthQueueDiscipline.valueOf(it) }
        val suffix = queue?.let { "-${it.name.lowercase()}" } ?: ""
        val sentSizes = DcDatagramSizes(
            nodeCount,
            from = runConfig.slotTimes[runConfig.warmupSlots],
            until = runConfig.slotTimes.last() + slotDuration
        )
        val report = DcScenario.run(
            network = network,
            graph = graph,
            config = runConfig,
            messageSchedules = schedules,
            extraDatagramRecorder = sentSizes,
            bandwidthQueueDiscipline = queue ?: BandwidthQueueDiscipline.SHADOW_LIKE,
            onComplete = { factory, datagrams ->
                val kinds = mapOf(
                    DcSlotMessageType.BLOCK to 10,
                    DcSlotMessageType.PAYLOAD to 11,
                    DcSlotMessageType.BLOB_COLUMN to 4,
                    DcSlotMessageType.FFG_ATTESTATION to 8,
                    DcSlotMessageType.AGGREGATES to 9
                )
                val messagesById = schedules.flatMap { it.messages }.associateBy { it.id }
                File(runDir, "jvm_arrivals$suffix.csv").printWriter().use { out ->
                    out.println("node,slot,kind,subnet,attester,delay_ms,voted_block")
                    factory.messageRecorders.forEach { (type, recorder) ->
                        recorder.deliveries().forEach { delivery ->
                            val message = messagesById.getValue(delivery.messageId)
                            out.println(
                                "${delivery.receiverNodeId},${delivery.slotIndex},${kinds.getValue(type)}," +
                                    "${message.subnetId ?: -1},-1,${delivery.latency.inWholeMilliseconds},false"
                            )
                        }
                    }
                }
                // The shape of eth-slot-sim's simnet traffic.json: UDP per slot, and the gossip
                // bytes received over the measured slots by kind and control field.
                val programs = factory.programs.associateBy { it.simNodeId }
                File(runDir, "jvm_traffic$suffix.json").writeText(
                    buildString {
                        append("{\"slot_seconds\":${slotDuration.inWholeSeconds},\"from_slot\":${runConfig.warmupSlots},\"nodes\":[")
                        (0 until nodeCount).forEach { node ->
                            if (node > 0) append(',')
                            val udp = runConfig.slotTimes.joinToString(",") { start ->
                                val t = datagrams.totals(setOf(node), start, start + slotDuration)
                                "{\"Sent\":${t.bytesSent},\"Recv\":${t.bytesReceived}," +
                                    "\"PacketsSent\":${t.packetsSent},\"PacketsRecv\":${t.packetsReceived}}"
                            }
                            val counter = programs.getValue(node).gossipByteCounter
                            fun byKind(bytes: Map<DcSlotMessageType, List<Long>>) =
                                bytes.entries.joinToString(",") { (type, buckets) -> "\"${kinds.getValue(type)}\":${buckets.sum()}" }
                            val control = counter.controlBreakdownReadMeasured
                            append(
                                "{\"node\":$node,\"udp\":[$udp]," +
                                    "\"unique\":{${byKind(counter.uniqueMessageBytesReadByTypeAndBucket)}}," +
                                    "\"dup\":{${byKind(counter.duplicateMessageBytesReadByTypeAndBucket)}}," +
                                    "\"control\":{\"ihave\":${control.ihaveBytes},\"iwant\":${control.iwantBytes}," +
                                    "\"idontwant\":${control.idontwantBytes},\"graft\":${control.graftBytes}," +
                                    "\"prune\":${control.pruneBytes},\"subs\":${control.subscriptionBytes}," +
                                    "\"other\":${control.framingBytes}}," +
                                    "\"sent_sizes\":{${sentSizes.sentSizes(node).entries.joinToString(",") { "\"${it.key}\":${it.value}" }}}}"
                            )
                        }
                        append("]}")
                    }
                )
            }
        )
        println(report)
    }

    /**
     * Host `i` reaches the rest of the network only through its own router, over an access link
     * carrying its upload and download rate; routers are joined by one fast link per peer edge.
     * Every link needs a latency above zero -- the parallel controller advances by the smallest one
     * -- so each access link takes [ACCESS_LATENCY] off the edge's, keeping the host-to-host
     * one-way latency at exactly the edge's.
     */
    private fun hostRouterTopology(
        hostIds: List<String>,
        nodes: List<Map<*, *>>,
        edges: List<Triple<Int, Int, Int>>
    ): QuicNetworkTopology {
        val routerOf = hostIds.map { "router-$it" }
        val accessLinks = hostIds.indices.flatMap { i ->
            val up = mbitPerSecond((nodes[i]["upload_bw_mbps"] as Number).toLong()).bytesPerSecond
            val down = mbitPerSecond((nodes[i]["download_bw_mbps"] as Number).toLong()).bytesPerSecond
            listOf(
                QuicNetworkLink(hostIds[i], routerOf[i], ACCESS_LATENCY, up),
                QuicNetworkLink(routerOf[i], hostIds[i], ACCESS_LATENCY, down)
            )
        }
        val coreBandwidth = mbitPerSecond(1_000_000).bytesPerSecond
        val coreLinks = edges.map { (a, b, latencyMs) ->
            require(latencyMs.milliseconds > ACCESS_LATENCY * 2) { "edge $a-$b latency ${latencyMs}ms is too small" }
            QuicNetworkLink(routerOf[a], routerOf[b], latencyMs.milliseconds - ACCESS_LATENCY * 2, coreBandwidth)
        }
        return QuicNetworkTopology(
            hosts = hostIds.map { QuicNetworkHost(it) },
            routers = routerOf.map { QuicNetworkRouter(it) },
            links = accessLinks + coreLinks
        )
    }

    companion object {
        private val ACCESS_LATENCY = 250.microseconds
    }

    @Suppress("UNCHECKED_CAST")
    private fun readJson(file: File): Map<String, Any?> =
        Yaml(LoaderOptions().apply { codePointLimit = 64 * 1024 * 1024 }).load(file.readText()) as Map<String, Any?>
}
