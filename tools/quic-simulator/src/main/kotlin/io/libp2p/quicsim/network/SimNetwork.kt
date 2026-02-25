package io.libp2p.quicsim.network

/**
 * Static network topology for the simulator engine.
 */
interface SimNetwork {
    /** All nodes participating in this topology. */
    val nodes: List<SimNode>
    /** Directed links between nodes. */
    val links: List<SimLink>
}
