package io.libp2p.quicsim.runner.graph

import kotlin.time.Duration

interface TimedNetworkLink<TVert : TimedNetworkVertex> {
    val left: TVert
    val right: TVert
    val latency: Duration

    companion object {
        fun TimedNetworkLink<*>.connects(vertex: TimedNetworkVertex): Boolean =
            left === vertex || right === vertex

        fun <TVert : TimedNetworkVertex> TimedNetworkLink<TVert>.other(vertex: TimedNetworkVertex): TVert =
            when (vertex) {
                left -> right
                right -> left
                else -> error("Link $this is not connected to vertex ${vertex.id}")
            }

    }
}
