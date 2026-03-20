package io.libp2p.quicsim.network2

import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network.SimQueueDiscipline
import java.time.Duration


/**
 * Directed link from one node to another.
 *
 * In a star topology this is usually peer -> router or router -> peer.
 *
 * Each directed link owns its own queue discipline via [qdisc].
 */
data class SimLink2(
    /** Sender side of this directed link. */
    val from: SimNode,
    /** Receiver side of this directed link. */
    val to: SimNode,
    /** Egress queue discipline applied on this directed link. */
    val qdisc: SimQueueDiscipline2
)
