package io.libp2p.quicsim.runner

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class PullingParallelTaskExecutor(
    private val parallelism: Int,
    private val exceptionHandler: (Throwable) -> Unit = {
        it.printStackTrace()
    }
) : AutoCloseable {

    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    private val executor = Executors.newFixedThreadPool(parallelism)

    fun execute(taskSupplier: () -> Runnable?) {
        val lock = Object()
        val failure = AtomicReference<Throwable>()
        var inFlightTasks = 0
        var completed = false

        fun awaitNextTask(): Runnable? =
            synchronized(lock) {
                while (failure.get() == null && !completed) {
                    val task = try {
                        taskSupplier()
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                        completed = true
                        lock.notifyAll()
                        return null
                    }

                    if (task != null) {
                        inFlightTasks++
                        return task
                    }

                    if (inFlightTasks == 0) {
                        completed = true
                        lock.notifyAll()
                        return null
                    }

                    try {
                        lock.wait()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        failure.compareAndSet(null, e)
                        completed = true
                        lock.notifyAll()
                        return null
                    }
                }
                null
            }

        fun taskFinished() =
            synchronized(lock) {
                inFlightTasks--
                lock.notifyAll()
            }

        val workers = (0 until parallelism).map {
            executor.submit {
                while (failure.get() == null) {
                    val task = awaitNextTask() ?: return@submit

                    try {
                        task.run()
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        taskFinished()
                    }
                }
            }
        }

        workers.forEach { worker ->
            try {
                worker.get()
            } catch (e: ExecutionException) {
                failure.compareAndSet(null, e.cause ?: e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                failure.compareAndSet(null, e)
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        failure.get()?.let { throw it }
    }

    override fun close() {
        executor.shutdown()
    }
}
