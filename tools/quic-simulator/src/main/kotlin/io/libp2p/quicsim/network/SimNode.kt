package io.libp2p.quicsim.network

/**
 * Generic network node in the simulator graph.
 *
 * A node can represent either an endpoint peer or an intermediate forwarding node.
 */
interface SimNode {
    /**
     * Stable node identifier used by links and packets.
     */
    val id: String
}
