package io.libp2p.quicsim.runner

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    @Test
    fun `waits for running tasks before treating null as completion`() {
        val initialStarted = CountDownLatch(2)
        val initialDone = AtomicInteger()
        val secondWaveRunning = AtomicInteger()
        val maxSecondWaveRunning = AtomicInteger()
        val secondWaveExecuted = AtomicInteger()
        var initialTasks = 2
        var secondWaveTasks = 4

        PullingParallelTaskExecutor(parallelism = 4).use { executor ->
            executor.execute {
                when {
                    initialTasks > 0 -> {
                        initialTasks--
                        Runnable {
                            initialStarted.countDown()
                            assertTrue(initialStarted.await(1, TimeUnit.SECONDS))
                            initialDone.incrementAndGet()
                        }
                    }
                    initialDone.get() == 2 && secondWaveTasks > 0 -> {
                        secondWaveTasks--
                        Runnable {
                            val running = secondWaveRunning.incrementAndGet()
                            maxSecondWaveRunning.updateAndGet { maxOf(it, running) }
                            Thread.sleep(20)
                            secondWaveExecuted.incrementAndGet()
                            secondWaveRunning.decrementAndGet()
                        }
                    }
                    else -> null
                }
            }
        }

        assertEquals(4, secondWaveExecuted.get())
        assertTrue(maxSecondWaveRunning.get() > 1)
    }
}
