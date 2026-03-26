package io.libp2p.quicsim.udpnetwork


/**
 * Directed link from one node to another.
 *
 * In a star topology this is usually peer -> router or router -> peer.
 *
 * Each directed link owns its own queue discipline via [qdisc].
 */
data class UdpSimLink(
    /** Sender side of this directed link. */
    val from: UdpSimNode,
    /** Receiver side of this directed link. */
    val to: UdpSimNode,
    /** Egress queue discipline applied on this directed link. */
    val qdisc: UdpSimQueueDiscipline
)
