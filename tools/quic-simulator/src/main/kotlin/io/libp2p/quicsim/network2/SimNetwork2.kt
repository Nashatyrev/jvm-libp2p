package io.libp2p.quicsim.network2

import io.libp2p.quicsim.network.SimLink
import io.libp2p.quicsim.network.SimNode

/**
 * Static network topology for the simulator engine.
 */
interface SimNetwork2 {
    /** All nodes participating in this topology. */
    val nodes: List<SimNode>
    /** Directed links between nodes. */
    val links: List<SimLink2>
}
