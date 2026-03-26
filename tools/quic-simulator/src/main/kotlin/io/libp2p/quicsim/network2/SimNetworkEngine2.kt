package io.libp2p.quicsim.network2

import io.libp2p.quicsim.core.PacketProcessor
import io.libp2p.quicsim.network.SimNetwork
import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network.SimPacket
import java.net.NetworkInterface

/**
 * Time-driven simulation engine over a fixed [io.libp2p.quicsim.network.SimNetwork] topology.
 */
interface SimNetworkEngine2 : PacketProcessor<SimPacket> {

    /** Topology handled by this engine. */
    val network: SimNetwork2
}