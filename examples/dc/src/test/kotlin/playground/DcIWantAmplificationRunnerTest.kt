package playground

import io.libp2p.etc.types.millis
import io.libp2p.example.dc.Bandwidths
import io.libp2p.example.dc.DcAttackOutcome.Percentiles
import io.libp2p.example.dc.DcNetworkBuilder
import io.libp2p.example.dc.DcPublisherSelection
import io.libp2p.example.dc.DcRunConfig
import io.libp2p.example.dc.DcScenario
import io.libp2p.example.dc.DcSlotMessageConfig
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
 * 100 nodes with 30 peers each, all on symmetric links of one rate, eth ladder D=8/6/12/6 with a
 * 700 ms heartbeat, Gossip v1.2 (IDONTWANT on). One 1 MiB message per slot on a global topic, slots
 * far enough apart (60 s) that they never overlap.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*DcIWantAmplificationRunnerTest*" \
 *   -Ddc.bandwidthsMbps=10,1000 -Ddc.iwant=all_announcers,disciplined,none
 * ```
 *
 *  - `dc.bandwidthsMbps`: link rates swept, symmetric (default 10,20,50,100,1000)
 *  - `dc.iwant`: policies swept (default all_announcers,disciplined,none)
 *  - `dc.messageKiB`: message size (default 1024)
 *  - `dc.slots`: messages, one per slot; the first is warm-up and not counted (default 4)
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
        val slots = prop("dc.slots", "4").toInt()
        val nodeCount = 100
        val peers = 30

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
                    publisherSelection = DcPublisherSelection.RANDOM_NODE,
                    topics = DcSlotMessageTopics.Global
                )
            ),
            randomSeed = 1
        )
        println(
            "IWANT amplification: nodes=$nodeCount peers=$peers message=${messageBytes / 1024} KiB " +
                "slots=$slots bandwidths=$bandwidths Mbit/s policies=$policies"
        )

        val summary = mutableListOf<String>()
        for (mbps in bandwidths) for (policy in policies) {
            val params = GossipParams.builder()
                .D(8).DLow(6).DHigh(12).DLazy(6).heartbeatInterval(700.millis)
                .maxGossipMessageSize(2 * messageBytes)
                .iWantPolicy(policy)
                .build()
            val network = DcNetworkBuilder.world(randomSeed = 1)
                .addGroup(count = nodeCount) {
                    name = "nodes"
                    spreadOverRegions()
                    bandwidth = Bandwidths.symmetric(Bandwidths.mbitPerSecond(mbps))
                    validators = 1
                    this.peers = peers
                    gossipParams = params
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
            val published = recorder.published().map { it.message }.filter { it.slotIndex in measured }
            val deliveries = recorder.deliveries().filter { it.slotIndex in measured }
            val expected = published.sumOf { factory.expectedDeliveriesOf(it) }
            val latency = Percentiles.of(deliveries.map { it.latency })
            val programs = factory.programs

            // One message per slot, so what a node reads or asks for in a slot is about that message.
            // Copies arriving by IWANT are counted as the IWANT ids the node sent: in an honest
            // network every request is answered, so the two match up to retransmission limits.
            data class NodeMessage(val copies: Long, val pulled: Long)
            val perNodeMessage = programs.flatMap { program ->
                val counter = program.gossipByteCounter
                published
                    .filter { it.publisherNodeId != program.simNodeId }
                    .map {
                        NodeMessage(
                            counter.publishMessagesReadBySlot[it.slotIndex] ?: 0L,
                            counter.iwantIdsWrittenBySlot[it.slotIndex] ?: 0L
                        )
                    }
            }
            val copiesSorted = perNodeMessage.map { it.copies }.sorted()
            fun q(p: Double) = copiesSorted[(p * copiesSorted.size).toInt().coerceAtMost(copiesSorted.size - 1)]
            val copies = perNodeMessage.sumOf { it.copies }.toDouble() / deliveries.size
            val pulled = perNodeMessage.sumOf { it.pulled }.toDouble() / deliveries.size
            val publishBytes = programs.sumOf { program ->
                measured.sumOf { program.gossipByteCounter.publishBytesReadBySlot[it] ?: 0L }
            }.toDouble()
            val amplification = publishBytes / (deliveries.size.toDouble() * messageBytes)
            val mesh = programs.flatMap { it.finalMeshSizes }.average()

            val label = "$mbps Mbit/s policy=${policy.name.lowercase()}"
            println("=== $label  [${wallSeconds}s wall]")
            println("  delivered ${deliveries.size}/$expected  latency ${latency ?: "-"}")
            recorder.published().map { it.message }.forEach { message ->
                val perMessage = Percentiles.of(recorder.deliveries().filter { it.messageId == message.id }.map { it.latency })
                val tag = if (message.slotIndex in measured) "" else " (warm-up, not counted)"
                val slotCopies = programs.sumOf { it.gossipByteCounter.publishMessagesReadBySlot[message.slotIndex] ?: 0L }
                val slotPulled = programs.sumOf { it.gossipByteCounter.iwantIdsWrittenBySlot[message.slotIndex] ?: 0L }
                val receivers = factory.expectedDeliveriesOf(message).toDouble()
                println(
                    "    slot ${message.slotIndex}: ${perMessage ?: "-"}  copies/node=%.2f of which iwant=%.2f$tag"
                        .format(slotCopies / receivers, slotPulled / receivers)
                )
            }
            println(
                "  copies/node/msg: mean=%.2f (mesh %.2f + iwant %.2f) p10=%d p50=%d p90=%d max=%d  publish-bytes amplification=%.2fx  mesh=%.1f"
                    .format(copies, copies - pulled, pulled, q(0.1), q(0.5), q(0.9), copiesSorted.last(), amplification, mesh)
            )
            summary += listOf(
                "$mbps", policy.name.lowercase(),
                "%.4f".format(deliveries.size.toDouble() / expected),
                latency?.p50?.inWholeMilliseconds ?: "-", latency?.p90?.inWholeMilliseconds ?: "-",
                latency?.p99?.inWholeMilliseconds ?: "-", latency?.max?.inWholeMilliseconds ?: "-",
                "%.2f".format(copies), "%.2f".format(copies - pulled), "%.2f".format(pulled),
                "${q(0.5)}", "${q(0.9)}", "${copiesSorted.last()}", "%.2f".format(amplification), "%.1f".format(mesh)
            ).joinToString(",")
        }
        println("\nSUMMARY")
        println(
            "mbps,policy,delivered,p50_ms,p90_ms,p99_ms,max_ms,copies_mean,copies_mesh,copies_iwant," +
                "copies_p50,copies_p90,copies_max,byte_amplification,mesh"
        )
        summary.forEach(::println)
    }
}
