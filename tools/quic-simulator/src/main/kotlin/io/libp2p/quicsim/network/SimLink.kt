package io.libp2p.quicsim.network

import java.time.Duration


/**
 * Directed link from one node to another.
 *
 * In a star topology this is usually peer -> router or router -> peer.
 *
 * Each directed link owns its own queue discipline via [qdisc].
 */
data class SimLink(
    /** Sender side of this directed link. */
    val from: SimNode,
    /** Receiver side of this directed link. */
    val to: SimNode,
    /** One-way propagation latency. */
    val latency: Duration,
    /** Independent packet drop probability in range [0.0, 1.0]. */
    val lossProbability: Double = 0.0,
    /** Egress queue discipline applied on this directed link. */
    val qdisc: SimQueueDiscipline
)
