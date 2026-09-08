package io.libp2p.example.dc

import io.libp2p.quicsim.runner.DatagramPacketTraceEvent
import io.libp2p.quicsim.sim.SimNodeId
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration

/** One attestation, as scheduled before the run. */
data class DcAttestation(
    val id: Int,
    val waveIndex: Int,
    val attesterNodeId: SimNodeId,
    val subnetId: Int
)

/** An attestation arriving at a node. [latency] is measured from the publisher's send time. */
data class DcDelivery(
    val attestationId: Int,
    val waveIndex: Int,
    val receiverNodeId: SimNodeId,
    val subnetId: Int,
    val latency: Duration
)

/**
 * Collects publications and deliveries during a run. Node programs run on several simulator
 * threads, so both queues are concurrent.
 */
class DcAttestationRecorder {
    private val publications = ConcurrentLinkedQueue<DcAttestation>()
    private val deliveries = ConcurrentLinkedQueue<DcDelivery>()

    fun recordPublished(attestation: DcAttestation) {
        publications += attestation
    }

    fun recordDelivered(delivery: DcDelivery) {
        deliveries += delivery
    }

    fun published(): List<DcAttestation> = publications.sortedBy { it.id }

    fun deliveries(): List<DcDelivery> = deliveries.sortedWith(compareBy({ it.attestationId }, { it.receiverNodeId }))
}

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
    val mean: Duration?
) {
    val deliveryRatio: Double
        get() = if (expectedDeliveries == 0) 0.0 else actualDeliveries.toDouble() / expectedDeliveries

    val missingDeliveries: Int get() = expectedDeliveries - actualDeliveries

    override fun toString(): String = buildString {
        appendLine(
            "attestations=$publishedCount deliveries=$actualDeliveries/$expectedDeliveries " +
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
         * [expectedDeliveries] is passed in rather than derived from [deliveries], since the whole
         * point is to notice deliveries that never arrived.
         */
        fun of(
            published: List<DcAttestation>,
            deliveries: List<DcDelivery>,
            expectedDeliveries: Int
        ): DcDeliveryStats {
            val latencies = deliveries.map { it.latency }.sorted()
            return DcDeliveryStats(
                publishedCount = published.size,
                expectedDeliveries = expectedDeliveries,
                actualDeliveries = deliveries.size,
                p50 = latencies.percentile(0.50),
                p95 = latencies.percentile(0.95),
                p99 = latencies.percentile(0.99),
                min = latencies.firstOrNull(),
                max = latencies.lastOrNull(),
                mean = latencies.meanOrNull()
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
    val mesh: DcMeshStats? = null
) {
    val gossipControlBytesSent: Long get() = gossipBytesSent - gossipPublishBytesSent
    val gossipControlBytesReceived: Long get() = gossipBytesReceived - gossipPublishBytesReceived

    override fun toString(): String = buildString {
        append("overall: $overall")
        perWave.toSortedMap().forEach { (wave, stats) ->
            append("wave $wave: $stats")
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
                    gossipBytesSent, gossipBytesReceived,
                    gossipFraction * 100
                )
        )
        appendLine(
            "gossip publish bytes/node: sent=%.0f recv=%.0f (%d/%d total)"
                .format(
                    gossipPublishBytesSent.toDouble() / nodeCount,
                    gossipPublishBytesReceived.toDouble() / nodeCount,
                    gossipPublishBytesSent, gossipPublishBytesReceived
                )
        )
        appendLine(
            "gossip control bytes/node: sent=%.0f recv=%.0f (%d/%d total)"
                .format(
                    gossipControlBytesSent.toDouble() / nodeCount,
                    gossipControlBytesReceived.toDouble() / nodeCount,
                    gossipControlBytesSent, gossipControlBytesReceived
                )
        )
        mesh?.let { append(it) }
    }

    companion object {
        fun of(
            published: List<DcAttestation>,
            deliveries: List<DcDelivery>,
            expectedDeliveriesOf: (DcAttestation) -> Int,
            traffic: DcTrafficReport,
            gossipBytesSent: Long = 0,
            gossipBytesReceived: Long = 0,
            gossipPublishBytesSent: Long = 0,
            gossipPublishBytesReceived: Long = 0,
            mesh: DcMeshStats? = null
        ): DcAttestationReport {
            val deliveriesByWave = deliveries.groupBy { it.waveIndex }
            val publishedByWave = published.groupBy { it.waveIndex }
            return DcAttestationReport(
                overall = DcDeliveryStats.of(
                    published = published,
                    deliveries = deliveries,
                    expectedDeliveries = published.sumOf(expectedDeliveriesOf)
                ),
                perWave = publishedByWave.mapValues { (wave, wavePublished) ->
                    DcDeliveryStats.of(
                        published = wavePublished,
                        deliveries = deliveriesByWave[wave].orEmpty(),
                        expectedDeliveries = wavePublished.sumOf(expectedDeliveriesOf)
                    )
                },
                traffic = traffic,
                gossipBytesSent = gossipBytesSent,
                gossipBytesReceived = gossipBytesReceived,
                gossipPublishBytesSent = gossipPublishBytesSent,
                gossipPublishBytesReceived = gossipPublishBytesReceived,
                mesh = mesh
            )
        }
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
                avgPacketsSentPerNode, avgPacketsReceivedPerNode, packetsSent, packetsReceived,
                avgBytesSentPerNode, avgBytesReceivedPerNode, bytesSent, bytesReceived
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
    val perWave: Map<Int, DcTrafficStats>
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
         */
        fun of(
            events: List<DatagramPacketTraceEvent>,
            waveTimes: List<Duration>,
            completeAt: Duration,
            nodeCount: Int
        ): DcTrafficReport {
            val boundaries = waveTimes + completeAt
            val perWave = waveTimes.indices.associateWith { wave ->
                val start = boundaries[wave]
                val end = boundaries[wave + 1]
                DcTrafficStats.of(events.filter { it.at >= start && it.at < end }, nodeCount)
            }
            return DcTrafficReport(
                overall = DcTrafficStats.of(events, nodeCount),
                perWave = perWave
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
