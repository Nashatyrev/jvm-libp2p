package io.libp2p.quicsim.core.schedule

import kotlin.time.Duration

interface Controllable {

    /**
     * Advances the time by [advanceDuration] and execute all tasks (if any) scheduled at this time point.
     * @throws IllegalStateException if there is any task scheduled for earlier time point
     */
    fun advanceAndExecuteAll(advanceDuration: Duration)

    fun nextTaskDuration(): Duration?
}
