package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.UdpSimLatencyQueue


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

    val bandwidthQueue: FifoUdpSimBandwidthQueue,

    val latencyQueue: UdpSimLatencyQueue,

    /** Egress queue discipline applied on this directed link. Basically either `bandwidthQueue` -> `latencyQueue` or the opposite order */
    val qdisc: PacketProcessor<UdpSimPacket>,
)
