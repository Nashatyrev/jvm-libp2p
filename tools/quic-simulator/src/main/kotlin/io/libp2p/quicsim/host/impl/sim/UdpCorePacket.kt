package io.libp2p.quicsim.host.impl.sim

import io.libp2p.quicsim.core.SimCorePacket

data class UdpCorePacket(
    val srcNodeId: String,
    val dstNodeId: String,
    val envelope: DatagramEnvelope
) : SimCorePacket