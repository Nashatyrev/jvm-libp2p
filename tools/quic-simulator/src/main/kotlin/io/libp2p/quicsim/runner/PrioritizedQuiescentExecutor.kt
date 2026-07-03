package io.libp2p.quicsim.runner

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PrioritizedQuiescentExecutor(
    parallelism: Int,
) : AutoCloseable {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    private val lock = Object()
    private val inFlightTasks = AtomicInteger()
    private val failure = AtomicReference<Throwable>()
    private val submitSequence = AtomicLong()

    private class PrioritizedRunnable(
        val priority: Int,
        val sequence: Long,
        val delegate: () -> Unit
    ) : Runnable {
        override fun run() {
            delegate()
        }
    }

    private val executor = ThreadPoolExecutor(
        parallelism,
        parallelism,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(
            11,
            compareBy<Runnable>(
                { (it as PrioritizedRunnable).priority },
                { (it as PrioritizedRunnable).sequence }
            )
        )
    )

    fun submit(priority: Int, task: () -> Unit) {
        inFlightTasks.incrementAndGet()
        val runnable = PrioritizedRunnable(priority, submitSequence.getAndIncrement()) {
            try {
                task()
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                taskFinished()
            }
        }

        try {
            executor.execute(runnable)
        } catch (t: Throwable) {
            failure.compareAndSet(null, t)
            taskFinished()
            throw t
        }
    }

    fun awaitQuiescence() {
        synchronized(lock) {
            while (inFlightTasks.get() > 0) {
                lock.wait()
            }
        }
        failure.get()?.let { throw it }
    }

    override fun close() {
        executor.shutdown()
    }

    private fun taskFinished() = synchronized(lock) {
        if (inFlightTasks.decrementAndGet() == 0) {
            lock.notifyAll()
        }
    }
}
