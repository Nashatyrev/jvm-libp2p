package io.libp2p.quicsim.network

import java.time.Duration


/**
 * Directed link from one node to another.
 *
 * In a star topology this is usually peer -> router or router -> peer.
 */
data class SimLink(
    val from: SimNode,
    val to: SimNode,
    val bandwidthBytesPerSecond: Long,
    val latency: Duration,
    val lossProbability: Double = 0.0,
    val qdisc: SimQueueDiscipline
)
