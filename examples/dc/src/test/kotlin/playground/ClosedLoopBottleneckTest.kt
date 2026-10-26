package playground

import io.libp2p.example.dc.DcCompositeDatagramRecorder
import io.libp2p.example.dc.DcDatagramSizes
import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import io.libp2p.quicsim.program.DataChunkNodeProgramFactory.DataChunk
import io.libp2p.quicsim.runner.BandwidthQueueDiscipline
import io.libp2p.quicsim.runner.DatagramTrafficAggregate
import io.libp2p.quicsim.runner.SimulatedQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicNetworkHost
import io.libp2p.quicsim.scenario.QuicNetworkLink
import io.libp2p.quicsim.scenario.QuicNetworkRouter
import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.scenario.QuicScenario
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Closed-loop counterpart of [QueueTraceReplayTest]: real QUIC connections through one 25 Mbit
 * bottleneck, so the senders' congestion control and ACKs are in the loop. eth-slot-sim's simnet
 * runs the same scenario with go-libp2p / quic-go (work.dir/queue-cmp/closedloop, see its README).
 *
 * Node 0 (25 Mbit up, 50 down) sends to nodes 1..8 (1 Gbit) over one long-lived stream each, the way
 * gossipsub keeps one stream per peer. Three rounds, 6 s apart from 5 s: a 1 MiB payload to nodes
 * 1..7 at once, and 240 B votes every 20 ms for 3 s to node 8 (a connection of its own) and to node 1
 * (sharing its connection with a payload). One-way latency to node i is 20 + 15 (i - 1) ms.
 *
 * Writes `chunks.csv` (`idx,kind,to,sent_ms,done_ms`) and `udp.csv` (per node, over [5 s, end): bytes
 * and packets sent and received, ACK-sized packets sent) into `-Ddc.closedLoopOut`.
 *
 * ```
 * ./gradlew :examples:dc:simulation --tests "*ClosedLoopBottleneckTest*" -Ddc.closedLoopOut=<dir> [-Ddc.queue=FQ_CODEL]
 * ```
 */
@Tag("simulation")
class ClosedLoopBottleneckTest {

    @Test
    fun `payload burst and votes through one bottleneck`() {
        val out = File(System.getProperty("dc.closedLoopOut") ?: error("set -Ddc.closedLoopOut")).apply { mkdirs() }
        val queue = BandwidthQueueDiscipline.valueOf(System.getProperty("dc.queue") ?: "FQ_CODEL")

        val chunks = mutableListOf<Pair<String, DataChunk>>()
        repeat(ROUNDS) { round ->
            val t0 = FIRST_ROUND + ROUND_GAP * round
            (1..7).forEach { to -> chunks += "payload" to DataChunk(PAYLOAD_BYTES, t0, 0, to) }
            var t = Duration.ZERO
            while (t < VOTE_SPAN) {
                chunks += "vote-own" to DataChunk(VOTE_BYTES, t0 + t, 0, 8)
                chunks += "vote-shared" to DataChunk(VOTE_BYTES, t0 + t, 0, 1)
                t += VOTE_GAP
            }
        }

        val runEnd = FIRST_ROUND + ROUND_GAP * ROUNDS + 10.seconds
        val traffic = DatagramTrafficAggregate(100.milliseconds, NODES, runEnd + 10.seconds)
        val sizes = DcDatagramSizes(NODES, from = FIRST_ROUND, until = runEnd)
        val scenario = QuicScenario(
            name = "closed-loop-bottleneck",
            network = topology(),
            maxRunDuration = runEnd,
            createNodeProgramFactory = {
                // Large enough that every chunk goes out as one frame, as a gossip message would.
                DataChunkNodeProgramFactory(NODES, chunks.map { it.second }, packetSizeBytes = PAYLOAD_BYTES + 64)
            }
        )
        val result = SimulatedQuicScenarioRunner(
            latencyWindowParallelism = Runtime.getRuntime().availableProcessors(),
            bandwidthQueueDiscipline = queue,
            datagramPacketTraceRecorder = DcCompositeDatagramRecorder(listOf(traffic, sizes))
        ).run(scenario)

        val factory = result.nodeProgramFactory
        val sent = factory.chunkSends().associate { it.chunkIndex to it.sentAt }
        val done = factory.packetReceipts().groupBy { it.chunkIndex }.mapValues { (_, r) -> r.maxOf { it.receivedAt } }
        File(out, "chunks.csv").printWriter().use { w ->
            w.println("idx,kind,to,sent_ms,done_ms")
            chunks.forEachIndexed { i, (kind, c) ->
                val doneMs = done[i]?.let { "%.3f".format(it.inWholeMicroseconds / 1e3) } ?: "-1"
                w.println("$i,$kind,${c.to},${"%.3f".format((sent[i] ?: c.at).inWholeMicroseconds / 1e3)},$doneMs")
            }
        }
        File(out, "udp.csv").printWriter().use { w ->
            w.println("node,bytes_sent,bytes_recv,packets_sent,packets_recv,ack_sized_sent")
            (0 until NODES).forEach { n ->
                val t = traffic.totals(setOf(n), FIRST_ROUND, runEnd)
                val acks = sizes.sentSizes(n).filterKeys { it <= 80 }.values.sum()
                w.println("$n,${t.bytesSent},${t.bytesReceived},${t.packetsSent},${t.packetsReceived},$acks")
            }
        }
        File(out, "sizes.csv").printWriter().use { w ->
            w.println("node,size,count")
            (0 until NODES).forEach { n -> sizes.sentSizes(n).forEach { (size, count) -> w.println("$n,$size,$count") } }
        }
        println("queue=$queue chunks=${chunks.size} delivered=${done.size} -> $out")
    }

    /** One router; host i's access links carry its latency, so host-to-host latency is the sum. */
    private fun topology(): QuicNetworkTopology {
        val hosts = (0 until NODES).map { "node-$it" }
        val router = "router-0"
        val links = hosts.flatMapIndexed { i, host ->
            val (up, down) = if (i == 0) 25_000_000L to 50_000_000L else 1_000_000_000L to 1_000_000_000L
            listOf(
                QuicNetworkLink(host, router, accessLatency(i), up / 8),
                QuicNetworkLink(router, host, accessLatency(i), down / 8)
            )
        }
        return QuicNetworkTopology(hosts.map { QuicNetworkHost(it) }, listOf(QuicNetworkRouter(router)), links)
    }

    companion object {
        const val NODES = 9
        const val ROUNDS = 3
        const val PAYLOAD_BYTES = 1 shl 20
        const val VOTE_BYTES = 240
        val FIRST_ROUND = 5.seconds
        val ROUND_GAP = 6.seconds
        val VOTE_SPAN = 3.seconds
        val VOTE_GAP = 20.milliseconds

        /** Sender side 0.25 ms; receiver i the rest of its 20 + 15 (i - 1) ms one-way latency. */
        fun accessLatency(node: Int): Duration =
            if (node == 0) 250.microseconds else (20 + 15 * (node - 1)).milliseconds - 250.microseconds
    }
}
