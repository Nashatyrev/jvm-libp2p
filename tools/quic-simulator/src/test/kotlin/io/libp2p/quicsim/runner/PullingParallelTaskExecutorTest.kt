package io.libp2p.quicsim.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class PullingParallelTaskExecutorTest {

    @Test
    fun `executes tasks until supplier returns null`() {
        val nextTask = AtomicInteger()
        val executed = ConcurrentLinkedQueue<Int>()

        PullingParallelTaskExecutor(parallelism = 4).use { executor ->
            executor.execute {
                val taskId = nextTask.getAndIncrement()
                if (taskId >= 100) {
                    null
                } else {
                    Runnable { executed += taskId }
                }
            }
        }

        assertEquals((0 until 100).toList(), executed.sorted())
    }

    @Test
    fun `propagates task failure`() {
        val failure = IllegalStateException("boom")

        assertThrows(IllegalStateException::class.java) {
            PullingParallelTaskExecutor(parallelism = 2).use { executor ->
                executor.execute {
                    Runnable { throw failure }
                }
            }
        }
    }
}
