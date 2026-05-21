package io.libp2p.quicsim.core.schedule

import java.util.PriorityQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Deterministic in-memory scheduler driven by explicit time advancement.
 */
class DeterministicScheduler : Controllable, SimpleScheduler, MonotonicTimer {

    private data class ScheduledTask(
        val dueAt: Duration,
        val sequence: Long,
        val task: Runnable
    )

    private class DeterministicTimePoint(
        private val value: Duration
    ) : TimePoint {
        override fun minus(other: TimePoint): Duration {
            require(other is DeterministicTimePoint) {
                "Unsupported TimePoint implementation: ${other::class.qualifiedName}"
            }
            return value - other.value
        }
    }

    private var currentTime: Duration = ZERO
    private var sequence: Long = 0
    private val queue = PriorityQueue(
        compareBy<ScheduledTask> { it.dueAt }
            .thenBy { it.sequence }
    )

    override fun executeAfterDelay(delay: Duration, task: Runnable) {
        require(!delay.isNegative()) { "delay must be non-negative" }
        queue += ScheduledTask(
            dueAt = currentTime + delay,
            sequence = sequence++,
            task = task
        )
    }

    override fun advance(advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must be non-negative" }
        val targetTime = currentTime + advanceDuration
        val nextTask = queue.peek()
        require(nextTask == null || nextTask.dueAt >= targetTime) {
            "Cannot advance to $targetTime with pending task scheduled at ${nextTask.dueAt}"
        }
        currentTime = targetTime
    }

    override fun executePending() {
        while (true) {
            val next = queue.peek() ?: break
            if (next.dueAt > currentTime) break

            queue.poll()
            next.task.run()
        }
    }

    override fun nextTaskDuration(): Duration? {
        val next = queue.peek() ?: return null
        val untilNext = next.dueAt - currentTime
        return if (untilNext.isNegative()) ZERO else untilNext
    }

    override fun time(): TimePoint = DeterministicTimePoint(currentTime)
}
