package io.libp2p.quicsim.network2

/**
 * Generic network node in the simulator graph.
 *
 * A node can represent either an endpoint peer or an intermediate forwarding node.
 */
data class SimNode(val id: String)
