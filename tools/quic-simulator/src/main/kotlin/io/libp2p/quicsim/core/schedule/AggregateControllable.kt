package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration

open class AggregateControllable(
    private val controllables: List<Controllable>
) : Controllable {

    override fun advance(advanceDuration: Duration) {
        controllables.forEach { it.advance(advanceDuration) }
    }

    override fun executePending() {
        controllables.forEach { it.executePending() }
    }

    override fun nextTaskDuration(): Duration? =
        controllables
            .mapNotNull { it.nextTaskDuration() }
            .minOrNull()


}
