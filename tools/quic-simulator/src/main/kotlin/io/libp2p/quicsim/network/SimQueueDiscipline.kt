package io.libp2p.quicsim.network

/**
 * Time-driven queue discipline attached to one directed link.
 *
 * Implementations own internal queue state and internal simulated clock.
 */
interface SimQueueDiscipline {

    /**
     * Result of packet enqueue attempt.
     */
    enum class EnqueueDecision {
        /** Packet accepted into queue state. */
        QUEUED,
        /** Packet dropped during enqueue (for example queue full/AQM policy). */
        DROPPED
    }

    /**
     * Current internal simulated time of this queue (millis).
     */
    val currentTimeMillis: Long

    /**
     * Enqueues packet at [currentTimeMillis].
     */
    fun enqueue(packet: SimPacket): EnqueueDecision

    /**
     * Advances queue time up to [maxMillis] (inclusive), but stops earlier when at least one packet
     * can be dequeued.
     *
     * Rules:
     * - Must be monotonic: [maxMillis] must be >= [currentTimeMillis].
     * - If one or more packets are dequeued at time `t`, queue stops at the earliest such `t` and
     *   returns all packets dequeued exactly at `t`.
     * - If no packet is dequeued in (`currentTimeMillis`, `maxMillis`], queue stops at [maxMillis]
     *   and returns an empty packet list.
     * - After return, [currentTimeMillis] is the stop time (either `t` or [maxMillis]).
     */
    fun advanceUntilDequeueOr(maxMillis: Long): List<SimPacket>
}
