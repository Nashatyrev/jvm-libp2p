package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration

open class AggregateControllable(
    private val controllables: List<Controllable>
) : Controllable {

    override fun advanceAndExecuteAll(advanceDuration: Duration) {
        controllables.forEach {
            it.advanceAndExecuteAll(advanceDuration)
        }
    }

    override fun nextTaskDuration(): Duration? =
        controllables
            .mapNotNull { it.nextTaskDuration() }
            .minOrNull()


}
