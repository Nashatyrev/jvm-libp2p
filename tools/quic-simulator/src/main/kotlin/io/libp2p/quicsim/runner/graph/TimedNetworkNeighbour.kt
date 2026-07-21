package io.libp2p.quicsim.runner.graph

data class TimedNetworkNeighbour(
    val vertex: TimedNetworkVertex,
    val link: TimedNetworkLink
) {
    val latency
        get() = link.latency
}
