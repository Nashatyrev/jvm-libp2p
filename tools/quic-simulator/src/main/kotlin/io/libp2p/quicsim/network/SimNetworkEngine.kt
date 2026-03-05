package io.libp2p.quicsim.network

import io.libp2p.quicsim.core.PacketProcessor

/**
 * Time-driven simulation engine over a fixed [SimNetwork] topology.
 */
interface SimNetworkEngine : PacketProcessor<SimPacket> {

    /** Current internal simulated time of the engine (millis). */
    val currentTimeMillis: Long

    /** Topology handled by this engine. */
    val network: SimNetwork
}
