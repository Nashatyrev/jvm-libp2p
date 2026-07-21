package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.PacketProcessor
import io.netty.channel.socket.DatagramPacket

/**
 * Time-driven simulation engine over a fixed [UdpSimNetwork] topology.
 */
interface UdpSimNetworkEngine : PacketProcessor<DatagramPacket> {

    /** Topology handled by this engine. */
    val network: UdpSimNetwork
}
