package io.libp2p.quicsim.core.schedule

import io.libp2p.quicsim.core.NotifyingPacketEmitter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.PriorityQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class AggregateControllable2Test {

    @Test
    fun `reports next task relative to aggregate current time`() {
        val route = RecordingControllable()
        val aggregate = AggregateControllable2(listOf(route))

        route.scheduleAfter(10.milliseconds)
        assertEquals(10.milliseconds, aggregate.nextTaskDuration())

        aggregate.advance(4.milliseconds)
        assertEquals(6.milliseconds, aggregate.nextTaskDuration())

        aggregate.advance(6.milliseconds)
        assertEquals(listOf(10.milliseconds), route.executedAt)
        assertEquals(null, aggregate.nextTaskDuration())
    }

    @Test
    fun `executes all routes due at next task time`() {
        val first = RecordingControllable()
        val second = RecordingControllable()
        val aggregate = AggregateControllable2(listOf(first, second))

        first.scheduleAfter(10.milliseconds)
        second.scheduleAfter(10.milliseconds)

        aggregate.advance(aggregate.nextTaskDuration()!!)

        assertEquals(listOf(10.milliseconds), first.executedAt)
        assertEquals(listOf(10.milliseconds), second.executedAt)
        assertEquals(null, aggregate.nextTaskDuration())
    }

    @Test
    fun `activates route again after it becomes idle`() {
        val route = RecordingControllable()
        val aggregate = AggregateControllable2(listOf(route))

        route.scheduleAfter(10.milliseconds)
        aggregate.advance(aggregate.nextTaskDuration()!!)

        route.scheduleAfter(5.milliseconds)

        assertEquals(5.milliseconds, aggregate.nextTaskDuration())

        aggregate.advance(aggregate.nextTaskDuration()!!)
        assertEquals(listOf(10.milliseconds, 15.milliseconds), route.executedAt)
    }

    private class RecordingControllable : NotifyingPacketEmitter<Unit> {
        private data class Task(
            val dueAt: Duration,
            val sequence: Long
        )

        private var currentTime: Duration = Duration.ZERO
        private var sequence: Long = 0
        private val tasks = PriorityQueue(
            compareBy<Task> { it.dueAt }
                .thenBy { it.sequence }
        )
        private val listeners = mutableListOf<() -> Unit>()

        val executedAt = mutableListOf<Duration>()

        fun scheduleAfter(delay: Duration) {
            tasks += Task(currentTime + delay, sequence++)
            listeners.forEach { it() }
        }

        override fun addPacketAddedListener(listener: () -> Unit) {
            listeners += listener
        }

        override fun emitPackets(): List<Unit> =
            emptyList()

        override fun advance(advanceDuration: Duration) {
            currentTime += advanceDuration
        }

        override fun executePending() {
            while (true) {
                val next = tasks.peek() ?: break
                if (next.dueAt > currentTime) {
                    break
                }
                tasks.poll()
                executedAt += currentTime
            }
        }

        override fun nextTaskDuration(): Duration? =
            tasks.peek()?.let { task ->
                (task.dueAt - currentTime).coerceAtLeast(Duration.ZERO)
            }
    }
}
