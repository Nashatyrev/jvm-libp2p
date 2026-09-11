package io.libp2p.example.dc

import io.libp2p.quicsim.runner.DatagramPacketTraceEvent
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where in the slot cycle [GossipByteCounter] should attribute the wire bytes it sees, shared by
 * every node in a run so their counters all bucket the same way.
 *
 * [anchor] is the moment slot 0 begins — [DcAttestationConfig.warmup] — since every node's clock
 * starts at zero and advances in lockstep, `(now - anchor) mod slotDuration` is exactly how far into
 * the *current repetition* of the slot cycle `now` falls, with no need to know which specific
 * message (if any) is being read. That is what lets this bucket control and transport traffic
 * alongside message traffic: those have no publish time of their own to measure from.
 */
data class DcSlotProfileParams(
    val anchor: Duration,
    val slotDuration: Duration,
    val bucketDuration: Duration = DEFAULT_BUCKET_DURATION,
    val warmupWaves: Int = 0
) {
    init {
        require(slotDuration.isPositive()) { "slotDuration must be > 0, got $slotDuration" }
        require(bucketDuration.isPositive()) { "bucketDuration must be > 0, got $bucketDuration" }
        require(warmupWaves >= 0) { "warmupWaves must be >= 0, got $warmupWaves" }
    }

    val bucketCount: Int
        get() = ceil(slotDuration.inWholeNanoseconds.toDouble() / bucketDuration.inWholeNanoseconds).toInt()

    /**
     * Bucket index `now` falls into, or null before [anchor] or within [warmupWaves] — mesh
     * formation and the excluded leading waves are not part of the steady-state picture this
     * profile is for. [io.libp2p.example.dc.GossipByteCounter] and the UDP-side bucketing in
     * [DcSlotTrafficProfile.of] both call this, so the two attribute a byte to the same bucket.
     */
    fun bucketOf(now: Duration): Int? {
        if (now < anchor) return null
        val elapsedNanos = (now - anchor).inWholeNanoseconds
        val slotNanos = slotDuration.inWholeNanoseconds
        val waveIndex = elapsedNanos / slotNanos
        if (waveIndex < warmupWaves) return null
        val bucketNanos = bucketDuration.inWholeNanoseconds
        return ((elapsedNanos % slotNanos) / bucketNanos).toInt().coerceIn(0, bucketCount - 1)
    }

    companion object {
        /** Every point in a 12s slot at the default 100ms resolution: 120 buckets. */
        val DEFAULT_BUCKET_DURATION: Duration = 100.milliseconds
    }
}

/**
 * Average inbound bytes one node receives per slot, bucketed by where in the slot cycle they arrived
 * — bucket 0 is `[0, bucketDuration)` measured from the start of whichever slot is in progress,
 * bucket 1 the next [bucketDuration] window, and so on up to [slotDuration]. A run of many slots
 * overlays them into one picture of where in the slot traffic actually shows up, e.g. a block
 * published at a 2s offset should show as a spike in [uniqueMessageBytesPerNode] just after
 * `t=2000ms`.
 *
 * Four views of the same wire traffic, side by side so they can be compared bucket for bucket:
 *  - [uniqueMessageBytesPerNode]: gossip publish bytes, by message type, counted on a node's first
 *    sighting of each message — what the application layer would eventually see once, per message.
 *  - [duplicateMessageBytesPerNode]: the same, but for every copy after a node's first sighting —
 *    still genuine wire traffic a mesh peer forwarded before it learned the node already had this
 *    message, just not traffic the application layer ends up doing anything with.
 *  - [controlBytesPerNode]: the rest of what gossip put on the wire for these bytes — subscriptions,
 *    GRAFT/PRUNE/IHAVE/IWANT, and RPC framing. One column, not per type, since control traffic is not
 *    about any particular message.
 *  - [transportBytesPerNode]: raw inbound UDP bytes, with no attempt to net them against the gossip
 *    figures above. A per-bucket "UDP minus gossip" subtraction looks appealing but is not
 *    meaningful: gossip bytes are only counted once a full RPC has been reassembled from its QUIC
 *    packets, while UDP bytes are counted per packet as they arrive, so a large message spread over
 *    several packets shows its UDP bytes several buckets before the one gossip byte count that
 *    covers all of them — making a subtracted "overhead" figure swing wildly bucket to bucket
 *    without meaning anything. This column is simply the wire truth for the bucket, comparable
 *    against the gossip columns by eye but not netted against them.
 *
 * Every figure is bytes per node per slot: summed over every measured slot and every receiving node,
 * divided by [nodeCount] and [slotsMeasured] — the same "per network node" convention the rest of the
 * report uses — then rounded up to a whole byte so a bucket that saw any traffic at all reads as at
 * least 1 rather than rounding away to 0.
 */
data class DcSlotTrafficProfile(
    val bucketDuration: Duration,
    val slotDuration: Duration,
    val slotsMeasured: Int,
    val nodeCount: Int,
    /** One entry per bucket, in slot order, for every message type seen on the wire. */
    val uniqueMessageBytesPerNode: Map<DcSlotMessageType, List<Long>>,
    val duplicateMessageBytesPerNode: Map<DcSlotMessageType, List<Long>>,
    val controlBytesPerNode: List<Long>,
    val transportBytesPerNode: List<Long>
) {
    val bucketCount: Int get() = controlBytesPerNode.size

    /** Start of bucket [index] within the slot, e.g. bucket 0 starts at [Duration.ZERO]. */
    fun bucketStart(index: Int): Duration = bucketDuration * index

    override fun toString(): String = buildString {
        if (bucketCount == 0) return@buildString
        appendLine(
            "slot traffic profile (bytes/node/slot): bucket=$bucketDuration slotDuration=$slotDuration " +
                "slots=$slotsMeasured nodes=$nodeCount"
        )
        // Columns follow DcSlotMessageType declaration order, i.e. slot order.
        val types = (uniqueMessageBytesPerNode.keys + duplicateMessageBytesPerNode.keys).distinct().sorted()
        val columns = types.flatMap { listOf("${it.id}$UNIQUE_SUFFIX", "${it.id}$DUPLICATE_SUFFIX") } +
            CONTROL_COLUMN +
            TRANSPORT_COLUMN
        val zeros = List(bucketCount) { 0L }
        val series = types.flatMap { type ->
            listOf(
                "${type.id}$UNIQUE_SUFFIX" to (uniqueMessageBytesPerNode[type] ?: zeros),
                "${type.id}$DUPLICATE_SUFFIX" to (duplicateMessageBytesPerNode[type] ?: zeros)
            )
        }.toMap() + (CONTROL_COLUMN to controlBytesPerNode) + (TRANSPORT_COLUMN to transportBytesPerNode)
        val widths = columns.associateWith { maxOf(it.length, VALUE_WIDTH) }
        appendLine(
            "%${TIME_WIDTH}s".format("t(ms)") +
                columns.joinToString("") { "  %${widths.getValue(it)}s".format(it) }
        )
        for (bucket in 0 until bucketCount) {
            appendLine(
                "%${TIME_WIDTH}d".format(bucketStart(bucket).inWholeMilliseconds) +
                    columns.joinToString("") { column ->
                        "  %${widths.getValue(column)}d".format(series.getValue(column)[bucket])
                    }
            )
        }
    }

    companion object {
        private const val TIME_WIDTH = 8
        private const val VALUE_WIDTH = 8
        const val UNIQUE_SUFFIX = "-uniq"
        const val DUPLICATE_SUFFIX = "-dup"
        const val CONTROL_COLUMN = "control"
        const val TRANSPORT_COLUMN = "transport"

        /** Every point in a 12s slot at the default 100ms resolution: 120 buckets. */
        val DEFAULT_BUCKET_DURATION: Duration = DcSlotProfileParams.DEFAULT_BUCKET_DURATION

        /**
         * Aggregates every node's [GossipByteCounter] plus the run's raw inbound UDP datagrams into
         * one traffic profile. Callers restrict this to a subset of nodes — [gossipCounters],
         * [inboundEvents] and [nodeCount] all narrowed to one node group, say — to get that group's
         * own profile rather than the whole network's; see [DcGroupStats.slotTraffic].
         *
         * [gossipCounters] and [inboundEvents] must have been built with the same [params] the
         * counters were themselves constructed with — [io.libp2p.example.dc.DcAttestationNodeProgram]
         * guarantees this by deriving both from the same [DcAttestationConfig].
         */
        fun of(
            gossipCounters: Collection<GossipByteCounter>,
            inboundEvents: List<DatagramPacketTraceEvent>,
            params: DcSlotProfileParams,
            nodeCount: Int,
            slotsMeasured: Int
        ): DcSlotTrafficProfile {
            require(nodeCount > 0) { "nodeCount must be > 0, got $nodeCount" }
            require(slotsMeasured > 0) { "slotsMeasured must be > 0, got $slotsMeasured" }
            val bucketCount = params.bucketCount

            val rawUniqueBytes = mutableMapOf<DcSlotMessageType, LongArray>()
            val rawDuplicateBytes = mutableMapOf<DcSlotMessageType, LongArray>()
            val rawControlBytes = LongArray(bucketCount)
            gossipCounters.forEach { counter ->
                addInto(rawUniqueBytes, counter.uniqueMessageBytesReadByTypeAndBucket, bucketCount)
                addInto(rawDuplicateBytes, counter.duplicateMessageBytesReadByTypeAndBucket, bucketCount)
                counter.controlBytesReadByBucket.forEachIndexed { bucket, bytes ->
                    rawControlBytes[bucket] = rawControlBytes[bucket] + bytes
                }
            }

            val rawUdpBytes = LongArray(bucketCount)
            inboundEvents
                .asSequence()
                .filter { it.direction == DatagramPacketTraceEvent.Direction.INBOUND }
                .forEach { event ->
                    params.bucketOf(event.at)?.let { bucket -> rawUdpBytes[bucket] = rawUdpBytes[bucket] + event.bytes }
                }

            val denom = nodeCount.toDouble() * slotsMeasured
            fun average(raw: LongArray): List<Long> = raw.map { ceil(it / denom).toLong() }

            return DcSlotTrafficProfile(
                bucketDuration = params.bucketDuration,
                slotDuration = params.slotDuration,
                slotsMeasured = slotsMeasured,
                nodeCount = nodeCount,
                uniqueMessageBytesPerNode = rawUniqueBytes.mapValues { (_, raw) -> average(raw) },
                duplicateMessageBytesPerNode = rawDuplicateBytes.mapValues { (_, raw) -> average(raw) },
                controlBytesPerNode = average(rawControlBytes),
                transportBytesPerNode = average(rawUdpBytes)
            )
        }

        /** Adds one node's per-(type,bucket) counts into the running network-wide totals. */
        private fun addInto(
            totals: MutableMap<DcSlotMessageType, LongArray>,
            perNode: Map<DcSlotMessageType, List<Long>>,
            bucketCount: Int
        ) {
            perNode.forEach { (type, buckets) ->
                val arr = totals.getOrPut(type) { LongArray(bucketCount) }
                buckets.forEachIndexed { bucket, bytes -> arr[bucket] = arr[bucket] + bytes }
            }
        }
    }
}
