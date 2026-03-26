package io.libp2p.quicsim.udpnetwork

/**
 * Static network topology for the simulator engine.
 */
interface UdpSimNetwork {
    /** All nodes participating in this topology. */
    val nodes: List<UdpSimNode>
    /** Directed links between nodes. */
    val links: List<UdpSimLink>
}
