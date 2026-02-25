package io.libp2p.quicsim.network

/**
 * Time-driven simulation engine over a fixed [SimNetwork] topology.
 */
interface SimNetworkEngine {

    /**
     * Current internal simulated time of the engine (millis).
     */
    val currentTimeMillis: Long

    /** Topology handled by this engine. */
    val network: SimNetwork

    /**
     * Injects packet into the engine at [currentTimeMillis].
     */
    fun injectPacket(packet: SimPacket)

    /**
     * Advances simulated time up to [maxMillis] (inclusive), but stops earlier when at least one packet
     * is delivered.
     *
     * Rules:
     * - Must be monotonic: [maxMillis] must be >= [currentTimeMillis].
     * - If one or more packets are delivered at time `t`, engine stops at the earliest such `t` and
     *   returns all packets delivered exactly at `t`.
     * - If no packet is delivered in (`currentTimeMillis`, `maxMillis`], engine stops at [maxMillis]
     *   and returns an empty packet list.
     * - After return, [currentTimeMillis] is the stop time (either `t` or [maxMillis]).
     */
    fun advanceUntilDeliveryOr(maxMillis: Long): List<SimPacket>
}
