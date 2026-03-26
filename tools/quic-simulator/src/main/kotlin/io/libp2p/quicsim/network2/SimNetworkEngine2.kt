package io.libp2p.quicsim.network2

import io.libp2p.quicsim.core.PacketProcessor

/**
 * Time-driven simulation engine over a fixed [SimNetwork2] topology.
 */
interface SimNetworkEngine2 : PacketProcessor<SimPacket> {

    /** Topology handled by this engine. */
    val network: SimNetwork2
}
