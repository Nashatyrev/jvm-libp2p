package io.libp2p.quicsim.core.schedule

import com.google.common.collect.Comparators.min
import kotlin.time.Duration

interface Controllable {

    /**
     * Advances local time without executing tasks scheduled at the new time.
     */
    fun advance(advanceDuration: Duration)

    /**
     * Executes tasks which are pending at the already advanced local time.
     */
    fun executePending()

    /**
     * Advances the time by [advanceDuration] and execute all tasks (if any) scheduled at this time point.
     * @throws IllegalStateException if there is any task scheduled for earlier time point
     */
    fun advanceAndExecuteAll(advanceDuration: Duration) {
        advance(advanceDuration)
        executePending()
    }

    fun nextTaskDuration(): Duration?

    companion object {

        fun Controllable.advanceAndExecuteUntil(advanceDuration: Duration) {
            var timeLeft = advanceDuration
            while (timeLeft > Duration.ZERO) {
                val nextAdvance = min(timeLeft, nextTaskDuration() ?: timeLeft)
                advance(nextAdvance)
                executePending()
                timeLeft -= nextAdvance
            }
        }

    }
}

