package io.libp2p.quicsim.host.impl.sim

import io.libp2p.quicsim.host.SimNodeId

data class BoundChannel(
    val ownerNodeId: SimNodeId,
    val channel: SimDatagramChannel
)