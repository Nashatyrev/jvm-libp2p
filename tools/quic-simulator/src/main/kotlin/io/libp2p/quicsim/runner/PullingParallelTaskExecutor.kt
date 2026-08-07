package io.libp2p.quicsim.runner

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class PullingParallelTaskExecutor(
    private val parallelism: Int,
) : AutoCloseable {

    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    private val executor = Executors.newFixedThreadPool(parallelism)

    fun execute(taskSupplier: () -> Runnable?) {
        val failure = AtomicReference<Throwable>()
        val workers = (0 until parallelism).map {
            executor.submit {
                while (failure.get() == null) {
                    val task = try {
                        taskSupplier()
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                        return@submit
                    } ?: return@submit

                    try {
                        task.run()
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
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
