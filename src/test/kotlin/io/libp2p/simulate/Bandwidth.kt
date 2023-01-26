package io.libp2p.simulate

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun interface BandwidthDelayer : MessageDelayer {

    companion object {
        val UNLIM_BANDWIDTH = BandwidthDelayer { CompletableFuture.completedFuture(null) }

        fun createMessageDelayer(
            outboundBandwidthDelayer: BandwidthDelayer,
            connectionLatencyDelayer: MessageDelayer,
            inboundBandwidthDelayer: BandwidthDelayer,
        ): MessageDelayer {
            return MessageDelayer { size ->
                CompletableFuture.allOf(
                    outboundBandwidthDelayer.delay(size)
                        .thenCompose { connectionLatencyDelayer.delay(size) },
                    connectionLatencyDelayer.delay(size)
                        .thenCompose { inboundBandwidthDelayer.delay(size) }
                ).thenApply { }
            }
        }
    }
}

data class Bandwidth(val bytesPerSecond: Long) {
    fun getTransmitTime(size: Long): Duration =
        (size * 1000 / bytesPerSecond).milliseconds
}

fun ScheduledExecutorService.delayedFuture(delay: Duration): CompletableFuture<Unit> {
    val fut = CompletableFuture<Unit>()
    this.schedule({ fut.complete(null) }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return fut
}

class SimpleBandwidthTracker(
    val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService
) : BandwidthDelayer {

    override fun delay(size: Long): CompletableFuture<Unit> =
        executor.delayedFuture(totalBandwidth.getTransmitTime(size))
}


class BandwidthTracker(
    val totalBandwidth: Bandwidth
) : BandwidthDelayer {

    override fun delay(size: Long): CompletableFuture<Unit> {
        TODO("Not yet implemented")
    }

    data class Message(
        val size: Long,
        val sendTime: Long
    )

    companion object {
        fun calcDeliverTimes(totalBandwidth: Bandwidth, msgs: List<Message>): List<Long> {
            assert(msgs.map { it.sendTime }.isOrdered())
            val totalSize = msgs.sumOf { it.size }
            val maxSize = msgs.maxOf { it.size }
            val totalTime = totalSize * 1000 / totalBandwidth.bytesPerSecond
            return msgs.map {
                val t = totalTime * it.size / maxSize
                it.sendTime + t
            }
        }

        private fun <T : Comparable<T>> Collection<T>.isOrdered() =
            this
                .windowed(2) { l -> l[1] >= l[0]}
                .all { it }
    }
}


