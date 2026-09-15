package io.libp2p.example.dc

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * A growable primitive buffer of delivery latencies, in nanoseconds.
 *
 * Percentiles need every sample sorted, and a 1M-validator run produces ~50M deliveries per slot —
 * several times that once they are also split per slot, per wave and per group. Held as
 * `List<Duration>` those samples are boxed: ~24 bytes and one pointer dereference each, so sorting
 * them is a comparison sort whose every comparison is a cache miss, and the run spent minutes of
 * single-threaded time in [DcDeliveryStats.of] doing exactly that. A `LongArray` sorted by
 * [java.util.Arrays.sort] is the same answer with no allocation per sample and no indirection.
 */
class DcLatencySamples(initialCapacity: Int = 16) {

    private var nanos = LongArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    /** Total of all samples. At ~5s per delivery a Long overflows past ~1.8e9 samples. */
    private var sumNanos: Long = 0

    val isEmpty: Boolean get() = size == 0

    fun add(latency: Duration) = add(latency.inWholeNanoseconds)

    fun add(latencyNanos: Long) {
        if (size == nanos.size) nanos = nanos.copyOf(size * 2)
        nanos[size++] = latencyNanos
        sumNanos += latencyNanos
    }

    /**
     * Sorts in place and returns a view for reading percentiles off. Cheaper than handing back a
     * copy, at the cost of reordering this buffer — which is why [DcDeliveryStats.of] is the only
     * caller and consumes a buffer once.
     */
    fun sortedView(): Sorted {
        java.util.Arrays.sort(nanos, 0, size)
        return Sorted(nanos, size, sumNanos)
    }

    /** A sorted prefix of a [DcLatencySamples] buffer, positional reads only. */
    class Sorted(private val nanos: LongArray, val size: Int, private val sumNanos: Long) {

        /**
         * Nearest-rank percentile, matching the previous `List<Duration>.percentile` exactly: the
         * sample at `ceil(fraction * size)`, one-based.
         */
        fun percentile(fraction: Double): Duration? {
            require(fraction in 0.0..1.0) { "fraction must be in [0, 1], got $fraction" }
            if (size == 0) return null
            val rank = kotlin.math.ceil(fraction * size).toInt().coerceIn(1, size)
            return nanos[rank - 1].nanoseconds
        }

        fun minOrNull(): Duration? = if (size == 0) null else nanos[0].nanoseconds
        fun maxOrNull(): Duration? = if (size == 0) null else nanos[size - 1].nanoseconds
        fun meanOrNull(): Duration? = if (size == 0) null else (sumNanos / size).nanoseconds
    }

    companion object {
        fun of(latencies: List<Duration>): DcLatencySamples =
            DcLatencySamples(latencies.size).also { samples -> latencies.forEach { samples.add(it) } }
    }
}
