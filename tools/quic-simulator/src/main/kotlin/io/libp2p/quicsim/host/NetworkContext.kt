package io.libp2p.quicsim.host

import io.libp2p.core.Host
import io.libp2p.core.multiformats.Multiaddr

data class NetworkContext(
    val myHost: Host,
    // nodeId -> Addr
    val allNodes: Map<SimNodeId, Multiaddr>,
)