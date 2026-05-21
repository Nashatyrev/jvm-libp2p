package io.libp2p.quicsim.core.schedule

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class DeterministicSchedulerTest {

    @Test
    fun `advance executes due tasks in due-time and insertion order`() {
        val scheduler = DeterministicScheduler()
        val executed = mutableListOf<String>()

        scheduler.executeAfterDelay(10.milliseconds, Runnable { executed += "A" })
        scheduler.executeAfterDelay(10.milliseconds, Runnable { executed += "B" })
        scheduler.executeAfterDelay(25.milliseconds, Runnable { executed += "C" })

        scheduler.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf("A", "B"), executed)
        assertEquals(15.milliseconds, scheduler.nextTaskDuration())

        scheduler.advanceAndExecuteAll(15.milliseconds)
        assertEquals(listOf("A", "B", "C"), executed)
        assertEquals(null, scheduler.nextTaskDuration())
    }

    @Test
    fun `scheduler rejects negative delays and advances`() {
        val scheduler = DeterministicScheduler()

        assertThrows(IllegalArgumentException::class.java) {
            scheduler.executeAfterDelay((-1).milliseconds, Runnable {})
        }
        assertThrows(IllegalArgumentException::class.java) {
            scheduler.advanceAndExecuteAll((-1).milliseconds)
        }
    }

    @Test
    fun `zero advance executes tasks due now`() {
        val scheduler = DeterministicScheduler()
        var runs = 0

        scheduler.executeAfterDelay(0.milliseconds, Runnable { runs += 1 })
        scheduler.advanceAndExecuteAll(0.milliseconds)

        assertEquals(1, runs)
        assertEquals(null, scheduler.nextTaskDuration())
    }

    @Test
    fun `advance rejects jumping past a scheduled task`() {
        val scheduler = DeterministicScheduler()

        scheduler.executeAfterDelay(5.milliseconds, Runnable {})

        assertThrows(IllegalArgumentException::class.java) {
            scheduler.advanceAndExecuteAll(10.milliseconds)
        }
    }

    @Test
    fun `task can schedule another task for a later exact advance`() {
        val scheduler = DeterministicScheduler()
        val executed = mutableListOf<String>()

        scheduler.executeAfterDelay(5.milliseconds, Runnable {
            executed += "A"
            scheduler.executeAfterDelay(3.milliseconds, Runnable { executed += "B" })
        })

        scheduler.advanceAndExecuteAll(5.milliseconds)
        scheduler.advanceAndExecuteAll(3.milliseconds)
        assertEquals(listOf("A", "B"), executed)
    }

    @Test
    fun `executeAtFixedRate runs periodically with deterministic time jumps`() {
        val scheduler = DeterministicScheduler()
        var runs = 0

        scheduler.executeAtFixedRate(
            initialDelay = 5.milliseconds,
            period = 10.milliseconds,
            task = Runnable { runs += 1 }
        )

        scheduler.advanceAndExecuteAll(4.milliseconds)
        assertEquals(0, runs)
        assertEquals(1.milliseconds, scheduler.nextTaskDuration())

        scheduler.advanceAndExecuteAll(1.milliseconds)
        assertEquals(1, runs)
        assertEquals(10.milliseconds, scheduler.nextTaskDuration())

        scheduler.advanceAndExecuteAll(10.milliseconds)
        scheduler.advanceAndExecuteAll(10.milliseconds)
        scheduler.advanceAndExecuteAll(10.milliseconds)
        assertEquals(4, runs)
    }

    @Test
    fun `executeAtFixedRate validates initialDelay and period`() {
        val scheduler = DeterministicScheduler()

        assertThrows(IllegalArgumentException::class.java) {
            scheduler.executeAtFixedRate(
                initialDelay = (-1).milliseconds,
                period = 1.milliseconds,
                task = Runnable {}
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            scheduler.executeAtFixedRate(
                initialDelay = 0.milliseconds,
                period = 0.milliseconds,
                task = Runnable {}
            )
        }
    }

    @Test
    fun `nextTaskDuration reflects closest scheduled task`() {
        val scheduler = DeterministicScheduler()

        scheduler.executeAfterDelay(20.milliseconds, Runnable {})
        scheduler.executeAfterDelay(7.milliseconds, Runnable {})
        scheduler.executeAfterDelay(12.milliseconds, Runnable {})

        assertEquals(7.milliseconds, scheduler.nextTaskDuration())
        scheduler.advanceAndExecuteAll(5.milliseconds)
        assertEquals(2.milliseconds, scheduler.nextTaskDuration())
    }

    @Test
    fun `time points are monotonic and subtractable`() {
        val scheduler = DeterministicScheduler()
        val t0 = scheduler.time()
        scheduler.advanceAndExecuteAll(12.milliseconds)
        val t1 = scheduler.time()

        assertEquals(12.milliseconds, t1 - t0)
        assertTrue((t1 - t0) > 0.milliseconds)
    }
}
