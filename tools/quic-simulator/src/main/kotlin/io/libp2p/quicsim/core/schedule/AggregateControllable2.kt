package io.libp2p.quicsim.core.schedule

import io.libp2p.quicsim.core.NotifyingPacketEmitter
import jdk.nashorn.internal.runtime.BitVector
import kotlin.time.Duration

open class AggregateControllable2(
    private val controllables: List<Controllable>
) : Controllable {

    override fun advance(advanceDuration: Duration) {
        TODO()
    }

    override fun executePending() {
        TODO()
    }


    override fun nextTaskDuration(): Duration? = TODO()
}
