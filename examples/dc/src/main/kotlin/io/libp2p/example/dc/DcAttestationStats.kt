package io.libp2p.example.dc

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
    val perWave: Map<Int, DcDeliveryStats>
) {
    override fun toString(): String = buildString {
        append("overall: $overall")
        perWave.toSortedMap().forEach { (wave, stats) ->
            append("wave $wave: $stats")
        }
    }

    companion object {
        fun of(
            published: List<DcAttestation>,
            deliveries: List<DcDelivery>,
            expectedDeliveriesOf: (DcAttestation) -> Int
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
