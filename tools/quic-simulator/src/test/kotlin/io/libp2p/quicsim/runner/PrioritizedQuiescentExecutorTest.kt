package io.libp2p.quicsim.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PrioritizedQuiescentExecutorTest {
    @Test
    @Timeout(5)
    fun `runs queued tasks by priority then submission order`() {
        val executor = PrioritizedQuiescentExecutor(1)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executionOrder = Collections.synchronizedList(mutableListOf<String>())

        try {
            executor.submit(100) {
                firstStarted.countDown()
                assertTrue(releaseFirst.await(1, TimeUnit.SECONDS))
                executionOrder += "blocker"
            }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))

            executor.submit(10) { executionOrder += "medium-1" }
            executor.submit(1) { executionOrder += "high" }
            executor.submit(10) { executionOrder += "medium-2" }

            releaseFirst.countDown()
            executor.awaitQuiescence()
        } finally {
            executor.close()
        }

        assertEquals(
            listOf("blocker", "high", "medium-1", "medium-2"),
            executionOrder
        )
    }

    @Test
    @Timeout(5)
    fun `awaitQuiescence rethrows first task failure after all tasks finish`() {
        val executor = PrioritizedQuiescentExecutor(1)
        val secondTaskCompleted = AtomicBoolean(false)

        try {
            executor.submit(0) { throw IllegalStateException("boom") }
            executor.submit(1) { secondTaskCompleted.set(true) }

            val failure = assertThrows(IllegalStateException::class.java) {
                executor.awaitQuiescence()
            }

            assertEquals("boom", failure.message)
            assertTrue(secondTaskCompleted.get())
        } finally {
            executor.close()
        }
    }
}
