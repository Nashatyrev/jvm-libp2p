package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.PacketProcessor

/**
 * Time-driven simulation engine over a fixed [UdpSimNetwork] topology.
 */
interface UdpSimNetworkEngine : PacketProcessor<UdpSimPacket> {

    /** Topology handled by this engine. */
    val network: UdpSimNetwork
}
