package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.Controllable.Companion.advanceAndExecuteUntil
import kotlin.time.Duration

/** Keeps the delegate at its last execution time while logical time advances separately. */
internal class DeferredControllable(
    private val delegate: Controllable,
    initialTime: Duration,
) {
    var executedThrough: Duration = initialTime
        private set

    fun hasTaskThrough(targetTime: Duration): Boolean {
        require(targetTime >= executedThrough) { "targetTime must not be before executedThrough" }
        return delegate.nextTaskDuration()
            ?.let { executedThrough + it <= targetTime }
            ?: false
    }

    fun executeThrough(targetTime: Duration) {
        require(targetTime >= executedThrough) { "targetTime must not be before executedThrough" }
        delegate.advanceAndExecuteUntil(targetTime - executedThrough)
        executedThrough = targetTime
    }
}
