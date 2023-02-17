package io.libp2p.simulate

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

fun interface MessageDelayer {
    fun delay(size: Long): CompletableFuture<Unit>

    companion object {
        val NO_DELAYER = MessageDelayer { CompletableFuture.completedFuture(null) }
    }
}

class TimeDelayer(
    val executor: ScheduledExecutorService,
    val delaySupplier: () -> Duration
) : MessageDelayer {

    override fun delay(size: Long): CompletableFuture<Unit> {
        val ret = CompletableFuture<Unit>()
        executor.schedule({
            ret.complete(null)
        }, delaySupplier().inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return ret
    }
}

fun MessageDelayer.sequential(executor: ScheduledExecutorService) =
    SequentialDelayer(this, executor)

class SequentialDelayer(
    val delegate: MessageDelayer,
    val executor: ScheduledExecutorService,
) : MessageDelayer {

    private var lastMessageFuture: CompletableFuture<Unit> = CompletableFuture.completedFuture(null)

    override fun delay(size: Long): CompletableFuture<Unit> {
        lastMessageFuture = lastMessageFuture.thenComposeAsync({
            delegate.delay(size)
        }, executor)
        return lastMessageFuture
    }
}
