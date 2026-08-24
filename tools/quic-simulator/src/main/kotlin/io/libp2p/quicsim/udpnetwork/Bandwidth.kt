package io.libp2p.quicsim.udpnetwork

import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

class Bandwidth(
    val bytesPerSecond: Long
) {
    init {
        require(bytesPerSecond > 0) { "bytesPerSecond must be > 0" }
    }

    fun durationToTransfer(bytes: Int): Duration {
        require(bytes >= 0) { "bytes must be >= 0" }
        if (bytes == 0) {
            return Duration.ZERO
        }
        val nanos = ((bytes.toLong() * NANOS_PER_SECOND) + bytesPerSecond - 1) / bytesPerSecond
        return nanos.coerceAtLeast(1L).nanoseconds
    }

    val isInfinite: Boolean
        get() = bytesPerSecond == INFINITE_BANDWIDTH

    override fun toString(): String {
        val units = listOf("B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s")
        var value = bytesPerSecond.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        val formatted = if (value >= 100 || value % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.1f", value)
        }
        return "$formatted ${units[unitIndex]}"
    }

    companion object {
        /** Sentinel rate for links without bandwidth shaping. */
        const val INFINITE_BANDWIDTH: Long = Long.MAX_VALUE
        val INFINITE = Bandwidth(INFINITE_BANDWIDTH)

        private const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
