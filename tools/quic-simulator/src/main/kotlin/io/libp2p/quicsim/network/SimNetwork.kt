package io.libp2p.quicsim.network

interface SimNetwork {
    val nodes: List<SimNode>
    val links: List<SimLink>
}