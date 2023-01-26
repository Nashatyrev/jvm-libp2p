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

class SequentialDelayer(val delegate: MessageDelayer) : MessageDelayer {

    private data class Message(
        val size: Long,
        val fut: CompletableFuture<Unit> = CompletableFuture()
    )

    private val msgQueue = ArrayDeque<Message>()

    override fun delay(size: Long): CompletableFuture<Unit> {
        synchronized(this) {
            val msg = Message(size)
            msgQueue += msg
            popFromQueue()
            return msg.fut
        }
    }

    private fun popFromQueue() {
        synchronized(this) {
            msgQueue.removeFirstOrNull()?.also { msg ->
                delegate
                    .delay(msg.size)
                    .thenAccept {
                        msg.fut.complete(null)
                        popFromQueue()
                    }
            }
        }
    }
}
