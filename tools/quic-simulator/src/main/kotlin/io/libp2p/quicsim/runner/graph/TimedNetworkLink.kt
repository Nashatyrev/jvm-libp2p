package io.libp2p.quicsim.runner.graph

import kotlin.time.Duration

data class TimedNetworkLink(
    val left: TimedNetworkVertex,
    val right: TimedNetworkVertex,
    val latency: Duration
) {
    init {
        require(left.id != right.id) { "link endpoints must be different" }
        require(!latency.isNegative()) { "latency must not be negative" }
    }

    fun connects(vertex: TimedNetworkVertex): Boolean =
        left === vertex || right === vertex

    fun other(vertex: TimedNetworkVertex): TimedNetworkVertex =
        when (vertex) {
            left -> right
            right -> left
            else -> error("Link $this is not connected to vertex ${vertex.id}")
        }
}
