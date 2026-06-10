package io.libp2p.quicsim.udpnetwork.impl

import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import kotlin.time.Duration

class ParallelNetworkEngine(
    val network: UdpSimNetwork
) : Controllable {

    override fun advance(advanceDuration: Duration) {
        TODO("Not yet implemented")
    }

    override fun executePending() {
        TODO("Not yet implemented")
    }

    override fun nextTaskDuration(): Duration? {
        TODO("Not yet implemented")
    }
}