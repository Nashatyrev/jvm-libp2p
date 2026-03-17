package io.libp2p.quicsim.core.schedule.impl

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.SimpleScheduler
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Delayed
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.nanoseconds

fun MonotonicTimer.toCurrentTimeSupplier(): CurrentTimeSupplier {
    val epochTimePoint = this@toCurrentTimeSupplier.time()
    return {
        (this@toCurrentTimeSupplier.time() - epochTimePoint).inWholeMilliseconds
    }
}

fun <T> SimpleScheduler.submitAfterDelay(delay: Duration, task: () -> T): CompletableFuture<T> {
    val ret = CompletableFuture<T>()
    this.executeAfterDelay(delay) {
        ret.complete(task())
    }
    return ret
}

private class ScheduledExecutorSimpleScheduler(
    val scheduledExecutorService: ScheduledExecutorService
) : SimpleScheduler {
    override fun executeAfterDelay(delay: Duration, task: Runnable) {
        scheduledExecutorService.schedule(task, delay.inWholeNanoseconds, TimeUnit.NANOSECONDS)
    }
}

private class SimpleSchedulerScheduledExecutorService(
    private val scheduler: SimpleScheduler
) : AbstractExecutorService(), ScheduledExecutorService {

    private val shutdown = AtomicBoolean(false)

    override fun shutdown() {
        shutdown.set(true)
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown.set(true)
        return Collections.emptyList<Runnable>()
    }

    override fun isShutdown(): Boolean = shutdown.get()

    override fun isTerminated(): Boolean = shutdown.get()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true

    override fun execute(command: Runnable) {
        schedule(command, 0, TimeUnit.NANOSECONDS)
    }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        val future = BaseScheduledFuture<Unit>(System.nanoTime() + unit.toNanos(delay))
        scheduleDelay(delay, unit) {
            if (future.isCancelledFlag() || shutdown.get()) return@scheduleDelay
            try {
                command.run()
                future.complete(Unit)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }

    override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> {
        val future = BaseScheduledFuture<V>(System.nanoTime() + unit.toNanos(delay))
        scheduleDelay(delay, unit) {
            if (future.isCancelledFlag() || shutdown.get()) return@scheduleDelay
            try {
                future.complete(callable.call())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }

    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> {
        require(period > 0) { "period must be > 0" }
        val recurring = RecurringScheduledFuture(
            scheduler = scheduler,
            shutdown = shutdown,
            command = command,
            firstDelayNanos = unit.toNanos(initialDelay),
            nextDelayNanosSupplier = { unit.toNanos(period) }
        )
        recurring.start()
        return recurring
    }

    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        require(delay > 0) { "delay must be > 0" }
        val recurring = RecurringScheduledFuture(
            scheduler = scheduler,
            shutdown = shutdown,
            command = command,
            firstDelayNanos = unit.toNanos(initialDelay),
            nextDelayNanosSupplier = { unit.toNanos(delay) }
        )
        recurring.start()
        return recurring
    }

    private fun scheduleDelay(delay: Long, unit: TimeUnit, task: () -> Unit) {
        val duration = if (delay <= 0) ZERO else unit.toNanos(delay).nanoseconds
        scheduler.executeAfterDelay(duration, Runnable { task() })
    }

    private open class BaseScheduledFuture<V>(
        private val scheduledAtNanos: Long
    ) : ScheduledFuture<V> {
        protected val delegate = CompletableFuture<V>()
        protected val cancelled = AtomicBoolean(false)

        fun isCancelledFlag(): Boolean = cancelled.get()

        fun complete(value: V) {
            delegate.complete(value)
        }

        fun completeExceptionally(t: Throwable) {
            delegate.completeExceptionally(t)
        }

        override fun getDelay(unit: TimeUnit): Long {
            val delayNanos = (scheduledAtNanos - System.nanoTime()).coerceAtLeast(0)
            return unit.convert(delayNanos, TimeUnit.NANOSECONDS)
        }

        override fun compareTo(other: Delayed): Int =
            java.lang.Long.compare(
                getDelay(TimeUnit.NANOSECONDS),
                other.getDelay(TimeUnit.NANOSECONDS)
            )

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            cancelled.set(true)
            return delegate.cancel(mayInterruptIfRunning)
        }

        override fun isCancelled(): Boolean = delegate.isCancelled

        override fun isDone(): Boolean = delegate.isDone

        @Throws(InterruptedException::class, ExecutionException::class)
        override fun get(): V = delegate.get()

        @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
        override fun get(timeout: Long, unit: TimeUnit): V = delegate.get(timeout, unit)
    }

    private class RecurringScheduledFuture(
        private val scheduler: SimpleScheduler,
        private val shutdown: AtomicBoolean,
        private val command: Runnable,
        private val firstDelayNanos: Long,
        private val nextDelayNanosSupplier: () -> Long
    ) : BaseScheduledFuture<Unit>(System.nanoTime() + firstDelayNanos) {

        fun start() {
            scheduleNext(firstDelayNanos)
        }

        private fun scheduleNext(delayNanos: Long) {
            val delay = if (delayNanos <= 0) ZERO else delayNanos.nanoseconds
            scheduler.executeAfterDelay(delay) {
                if (cancelled.get() || shutdown.get()) {
                    if (!isDone) complete(Unit)
                    return@executeAfterDelay
                }
                try {
                    command.run()
                    scheduleNext(nextDelayNanosSupplier())
                } catch (t: Throwable) {
                    completeExceptionally(t)
                }
            }
        }
    }
}

fun SimpleScheduler.toScheduledExecutorService(): ScheduledExecutorService {
    if (this is ScheduledExecutorSimpleScheduler) {
        return this.scheduledExecutorService
    }
    return SimpleSchedulerScheduledExecutorService(this)
}

fun ScheduledExecutorService.toSimpleScheduler(): SimpleScheduler =
    ScheduledExecutorSimpleScheduler(this)
