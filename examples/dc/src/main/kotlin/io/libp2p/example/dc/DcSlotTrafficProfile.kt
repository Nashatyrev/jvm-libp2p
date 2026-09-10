package io.libp2p.example.dc

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Average inbound bytes one node receives of each message type, bucketed by how far into its slot
 * the delivery landed — bucket 0 is `[0, bucketDuration)` measured from the slot's own start, bucket
 * 1 is the next [bucketDuration] window, and so on up to [slotDuration]. A run of many slots overlays
 * them into one picture of where in the slot each message type's bytes actually show up, e.g. a
 * block published at a 2s offset shows up as a spike in the buckets just after `t=2000ms`, however
 * many slots the run measured.
 *
 * A delivery's position within its slot is `publishOffset + latency`: [DcSlotMessageConfig
 * .publishOffset] is how far into the slot the message was sent, and latency is how long it then
 * took to arrive at this receiver, so the sum is exactly the receiver's own elapsed time since the
 * slot began — no need for wall-clock timestamps, since every node's scheduler starts at zero and
 * they advance in lockstep.
 *
 * Values are bytes per node per slot: summed across every delivery that landed in the bucket, over
 * every measured slot and every receiving node, then divided by [nodeCount] and [slotsMeasured] —
 * the same "per network node" convention the rest of the report uses (e.g.
 * [DcAttestationReport.publishBytesReceivedPerNodePerWave]), so figures here are directly comparable
 * to it. Bytes are the message's configured payload size ([DcSlotMessageConfig.sizeBytes]), not
 * gossip's own wire framing — the same distinction drawn everywhere else in this report.
 *
 * The average is rounded up to a whole byte rather than truncated or rounded to nearest, so a
 * bucket that saw any traffic at all — even 0.1 bytes/node/slot — reads as at least 1 rather than
 * rounding away to 0 and looking indistinguishable from a bucket that saw none.
 *
 * A delivery whose `publishOffset + latency` reaches or exceeds [slotDuration] belongs to no bucket
 * and is counted in [overflowDeliveries] instead of silently being dropped from the picture.
 */
data class DcSlotTrafficProfile(
    val bucketDuration: Duration,
    val slotDuration: Duration,
    val slotsMeasured: Int,
    val nodeCount: Int,
    /** One entry per bucket, in slot order, for every message type present in the run. */
    val averageBytesPerNode: Map<DcSlotMessageType, List<Long>>,
    /** Deliveries that arrived at or after [slotDuration] into their own slot, by message type. */
    val overflowDeliveries: Map<DcSlotMessageType, Int>
) {
    val bucketCount: Int get() = averageBytesPerNode.values.firstOrNull()?.size ?: 0

    /** Start of bucket [index] within the slot, e.g. bucket 0 starts at [Duration.ZERO]. */
    fun bucketStart(index: Int): Duration = bucketDuration * index

    override fun toString(): String = buildString {
        if (averageBytesPerNode.isEmpty()) return@buildString
        appendLine(
            "slot traffic profile (bytes/node/slot): bucket=$bucketDuration slotDuration=$slotDuration " +
                "slots=$slotsMeasured nodes=$nodeCount"
        )
        val types = averageBytesPerNode.keys.sortedBy { it.id }
        val widths = types.associateWith { maxOf(it.id.length, VALUE_WIDTH) }
        appendLine(
            "%${TIME_WIDTH}s".format("t(ms)") +
                types.joinToString("") { "  %${widths.getValue(it)}s".format(it.id) }
        )
        for (bucket in 0 until bucketCount) {
            appendLine(
                "%${TIME_WIDTH}d".format(bucketStart(bucket).inWholeMilliseconds) +
                    types.joinToString("") { type ->
                        "  %${widths.getValue(type)}d".format(averageBytesPerNode.getValue(type)[bucket])
                    }
            )
        }
        val withOverflow = overflowDeliveries.filterValues { it > 0 }
        if (withOverflow.isNotEmpty()) {
            appendLine(
                "slot traffic profile overflow (arrived at or after $slotDuration into their slot): " +
                    withOverflow.entries.joinToString { (type, count) -> "$type=$count" }
            )
        }
    }

    companion object {
        private const val TIME_WIDTH = 8
        private const val VALUE_WIDTH = 8

        /** Every point in a 12s slot at the default 100ms resolution: 120 buckets. */
        val DEFAULT_BUCKET_DURATION: Duration = 100.milliseconds

        /**
         * [deliveriesByType] and [configsByType] need not cover the same message types as each other
         * — a type with no deliveries yet still gets an all-zero column, since it is still part of
         * the picture the run configured.
         */
        fun of(
            deliveriesByType: Map<DcSlotMessageType, List<DcSlotMessageDelivery>>,
            configsByType: Map<DcSlotMessageType, DcSlotMessageConfig>,
            slotDuration: Duration,
            nodeCount: Int,
            slotsMeasured: Int,
            bucketDuration: Duration = DEFAULT_BUCKET_DURATION,
            warmupWaves: Int = 0
        ): DcSlotTrafficProfile {
            require(bucketDuration.isPositive()) { "bucketDuration must be > 0, got $bucketDuration" }
            require(slotDuration.isPositive()) { "slotDuration must be > 0, got $slotDuration" }
            require(nodeCount > 0) { "nodeCount must be > 0, got $nodeCount" }
            require(slotsMeasured > 0) { "slotsMeasured must be > 0, got $slotsMeasured" }

            val bucketCount = kotlin.math.ceil(
                slotDuration.inWholeMilliseconds.toDouble() / bucketDuration.inWholeMilliseconds
            ).toInt()
            val denom = nodeCount.toDouble() * slotsMeasured

            val averages = mutableMapOf<DcSlotMessageType, List<Long>>()
            val overflow = mutableMapOf<DcSlotMessageType, Int>()
            configsByType.forEach { (type, config) ->
                val bytes = DoubleArray(bucketCount)
                var over = 0
                deliveriesByType[type].orEmpty()
                    .filter { it.slotIndex >= warmupWaves }
                    .forEach { delivery ->
                        val timeInSlot = config.publishOffset + delivery.latency
                        val bucket = (timeInSlot.inWholeMilliseconds / bucketDuration.inWholeMilliseconds).toInt()
                        if (bucket in 0 until bucketCount) {
                            bytes[bucket] = bytes[bucket] + config.sizeBytes
                        } else {
                            over++
                        }
                    }
                averages[type] = bytes.map { kotlin.math.ceil(it / denom).toLong() }
                overflow[type] = over
            }
            return DcSlotTrafficProfile(
                bucketDuration = bucketDuration,
                slotDuration = slotDuration,
                slotsMeasured = slotsMeasured,
                nodeCount = nodeCount,
                averageBytesPerNode = averages,
                overflowDeliveries = overflow
            )
        }
    }
}
