package io.libp2p.quicsim.network

import io.libp2p.quicsim.core.PacketProcessor

/**
 * Time-driven simulation engine over a fixed [SimNetwork] topology.
 */
interface SimNetworkEngine : PacketProcessor<SimPacket> {

    /** Topology handled by this engine. */
    val network: SimNetwork
}
