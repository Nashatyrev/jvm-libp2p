package io.libp2p.quicsim.udpnetwork

/**
 * Generic network node in the simulator graph.
 *
 * A node can represent either an endpoint peer or an intermediate forwarding node.
 */
data class UdpSimNode(val id: String)
