package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.LatencyQueue
import io.libp2p.quicsim.core.PacketProcessor
import io.netty.channel.socket.DatagramPacket


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

    val bandwidthQueue: UdpSimBandwidthQueue,
    val latencyQueue: LatencyQueue<DatagramPacket>,

    /** Egress queue discipline applied on this directed link. Basically either `bandwidthQueue` -> `latencyQueue` or the opposite order */
    val qdisc: PacketProcessor<DatagramPacket>,
)
