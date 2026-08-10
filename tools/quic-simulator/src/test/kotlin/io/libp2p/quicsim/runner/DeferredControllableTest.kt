package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.Controllable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class DeferredControllableTest {

    @Test
    fun `does not advance an idle controllable`() {
        val controllable = RecordingControllable()
        val deferred = DeferredControllable(controllable, ZERO)

        assertFalse(deferred.hasTaskThrough(100.milliseconds))

        assertEquals(ZERO, controllable.totalAdvance)
        assertEquals(ZERO, deferred.executedThrough)
    }

    @Test
    fun `executes through accumulated logical time once a task is due`() {
        val controllable = RecordingControllable(nextTaskAt = 150.milliseconds)
        val deferred = DeferredControllable(controllable, ZERO)

        assertFalse(deferred.hasTaskThrough(100.milliseconds))
        assertTrue(deferred.hasTaskThrough(200.milliseconds))
        deferred.executeThrough(200.milliseconds)

        assertEquals(200.milliseconds, controllable.totalAdvance)
        assertEquals(200.milliseconds, deferred.executedThrough)
    }

    private class RecordingControllable(
        private var nextTaskAt: Duration? = null,
    ) : Controllable {
        var totalAdvance: Duration = ZERO
            private set

        override fun advance(advanceDuration: Duration) {
            totalAdvance += advanceDuration
        }

        override fun executePending() {
            if (nextTaskAt != null && totalAdvance >= nextTaskAt!!) {
                nextTaskAt = null
            }
        }

        override fun nextTaskDuration(): Duration? =
            nextTaskAt?.minus(totalAdvance)
    }
}
