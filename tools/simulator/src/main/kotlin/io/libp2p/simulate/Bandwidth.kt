package io.libp2p.simulate

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import java.lang.Long.max
import java.lang.Long.min
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

interface BandwidthDelayer : MessageDelayer {

    val totalBandwidth: Bandwidth

    companion object {
        val UNLIM_BANDWIDTH = object : BandwidthDelayer {
            override val totalBandwidth = Bandwidth(Long.MAX_VALUE)
            override fun delay(size: Long) = CompletableFuture.completedFuture(Unit)
        }

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
    fun getTransmitTimeMillis(size: Long): Long = (size * 1000 / bytesPerSecond)
    fun getTransmitTime(size: Long): Duration = getTransmitTimeMillis(size).milliseconds

    fun getTransmitSize(timeMillis: Long): Long =
        bytesPerSecond * timeMillis / 1000

    operator fun div(d: Int) = Bandwidth(bytesPerSecond / d)

    companion object {
        fun mbitsPerSec(mbsec: Int) = Bandwidth(mbsec.toLong() * (1 shl 20) / 10)
    }
}

fun ScheduledExecutorService.delayedFuture(delay: Duration): CompletableFuture<Unit> {
    val fut = CompletableFuture<Unit>()
    this.schedule({ fut.complete(null) }, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    return fut
}

class SimpleBandwidthTracker(
    override val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService
) : BandwidthDelayer {

    override fun delay(size: Long): CompletableFuture<Unit> =
        executor.delayedFuture(totalBandwidth.getTransmitTime(size))
}

class SequentialBandwidthTracker(
    override val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService
) : BandwidthDelayer {

    private var lastMessageFuture: CompletableFuture<Unit> = CompletableFuture.completedFuture(null)

    override fun delay(size: Long): CompletableFuture<Unit> {
        lastMessageFuture = lastMessageFuture.thenCompose {
            executor.delayedFuture(totalBandwidth.getTransmitTime(size))
        }
        return lastMessageFuture
    }
}


class BetterBandwidthTracker(
    override val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService,
    val timeSupplier: CurrentTimeSupplier
) : BandwidthDelayer {

    private val transferringMessages = mutableListOf<Message>()

    override fun delay(size: Long): CompletableFuture<Unit> {
        val msg = Message(size, sendTime = timeSupplier())
        transferringMessages += msg
        recalcDeliverTimes(totalBandwidth, transferringMessages)
        val fut = CompletableFuture<Unit>()
        scheduleMessageWakeup(msg, fut)
        return fut
    }

    private fun scheduleMessageWakeup(msg: Message, completion: CompletableFuture<Unit>) {
        val delay = msg.deliverTime - timeSupplier()
        executor.schedule({
            if (timeSupplier() >= msg.deliverTime) {
                transferringMessages -= msg
                completion.complete(null)
            } else {
                scheduleMessageWakeup(msg, completion)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    class Message(
        val size: Long,
        val sendTime: Long,
        var deliverTime: Long = 0
    )

    companion object {

        fun recalcDeliverTimes(totalBandwidth: Bandwidth, msgs: List<Message>, precision: Int = 10) {
            val deliverTimes = calcDeliverTimes(totalBandwidth, msgs, precision)
            msgs.zip(deliverTimes).forEach { (msg, t) ->  msg.deliverTime = t }
        }

        fun calcDeliverTimes(totalBandwidth: Bandwidth, msgs: List<Message>, precision: Int = 10): List<Long> {
            if (msgs.isEmpty()) return emptyList()
            if (msgs.size == 1) {
                val msg = msgs[0]
                return listOf(msg.sendTime + totalBandwidth.getTransmitTimeMillis(msg.size))
            }
            assert(msgs.map { it.sendTime }.isOrdered())
            val totalSize = msgs.sumOf { it.size }
            val totalTime = totalBandwidth.getTransmitTimeMillis(totalSize)
            if (totalTime < 2) {
                return msgs.map { it.sendTime }
            }
            val startTime = msgs.first().sendTime
            val timeWindows = (startTime until startTime + totalTime).split(precision)
            val remainingSizes = Array(msgs.size) { Array(precision) { 0L } }
            val winBands = Array(precision) { Bandwidth(0) }
            for (i in timeWindows.indices) {
                val tWin = timeWindows[i]
                for (j in msgs.indices) {
                    val msg = msgs[j]
                    val newRemainSize = if (msg.sendTime in tWin) {
                        msg.size
                    } else {
                        if (i == 0) {
                            0
                        } else {
                            val prevWinBand = winBands[i-1]
                            val prevWinBytesPerMsg = prevWinBand.getTransmitSize(tWin.length)
                            max(0L, remainingSizes[j][i-1] - prevWinBytesPerMsg)
                        }
                    }
                    remainingSizes[j][i] = newRemainSize
                }
                val msgCount = Integer.max(1, remainingSizes.count { it[i] > 0 })
                winBands[i] = totalBandwidth / msgCount
            }

            return msgs.map { msg ->
                var i = 0
                while (msg.sendTime !in timeWindows[i]) i++

                var remainingSize = msg.size
                var time = msg.sendTime

                while(i < precision) {
                    val timeWin = timeWindows[i]
                    val winBand = winBands[i]

                    val winTime = min(timeWin.length, timeWin.end - msg.sendTime)
                    val transmittedBytes = winBand.getTransmitSize(winTime)
                    if (transmittedBytes >= remainingSize) {
                        break
                    }
                    remainingSize -= transmittedBytes
                    time = timeWin.end
                    i++
                }

                val lastI = Integer.min(i, precision - 1)
                time + winBands[lastI].getTransmitTimeMillis(remainingSize)
            }
        }

        private val LongRange.end get() = this.last + 1
        private val LongRange.length get() = this.end - this.first

        fun LongRange.split(rangeCount: Int): List<LongRange> {
            val chunkLen = (this.last + 1 - this.first)
            val start = this.first * rangeCount
            return (0 until rangeCount)
                .map {
                    val s = start + it * chunkLen
                    s / rangeCount until (s + chunkLen) / rangeCount
                }
        }
    }
}


class AnotherBetterBandwidthTracker(
    override val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService,
    val timeSupplier: CurrentTimeSupplier,
    val name: String = "",
    val immediateExecutionBandwidth: Bandwidth = totalBandwidth / 10,
    val forceRemoveDeliveredMessagesOlderThan: Duration = 10.seconds
) : BandwidthDelayer {

    private val transferringMessages = mutableListOf<MessageData>()

    override fun delay(size: Long): CompletableFuture<Unit> {
        return if (immediateExecutionBandwidth.getTransmitTimeMillis(size) <= 1) {
            CompletableFuture.completedFuture(Unit)
        } else {
            prune()
            val msg = MessageData(Message(size, sendTime = timeSupplier()))
            transferringMessages += msg
            updateDeliverTimes(totalBandwidth, transferringMessages)
            val fut = CompletableFuture<Unit>()
            scheduleMessageWakeup(msg, fut)
            fut
        }
    }

    private fun scheduleMessageWakeup(msg: MessageData, completion: CompletableFuture<Unit>) {
        val delay = msg.deliverTime - timeSupplier()
        executor.schedule({
            if (timeSupplier() >= msg.deliverTime) {
                completion.complete(null)
                msg.delivered = true
                prune()
            } else {
                scheduleMessageWakeup(msg, completion)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun prune() {
        val curT = timeSupplier()
        transferringMessages.removeIf {
            it.delivered && (curT - it.deliverTime) > forceRemoveDeliveredMessagesOlderThan.inWholeMilliseconds
        }
        pruneIfAllDelivered()
    }

    private fun pruneIfAllDelivered() {
        if (transferringMessages.all { it.delivered }) {
            transferringMessages.clear()
        }
    }

    data class Message(
        val size: Long,
        val sendTime: Long
    )

    data class MessageData(
        val msg: Message,
        var deliverTime: Long = 0,
        var delivered: Boolean = false
    )

    companion object {

        fun updateDeliverTimes(totalBandwidth: Bandwidth, msgs: List<MessageData>) {
            val deliverTimes = calcDeliverTimes(totalBandwidth, msgs.map { it.msg })
            for (i in deliverTimes.indices) {
                msgs[i].deliverTime = deliverTimes[i]
            }
        }

        fun calcDeliverTimes(totalBandwidth: Bandwidth, msgs: List<Message>): LongArray {
            if (msgs.isEmpty()) return LongArray(0)
            if (msgs.size == 1) {
                val msg = msgs[0]
                val deliverTime = msg.sendTime + totalBandwidth.getTransmitTimeMillis(msg.size)
                return longArrayOf(deliverTime)
            }
            assert(msgs.map { it.sendTime }.isOrdered())

            data class IntMessage(
                val msg: Message,
                var lastUpdateSizeLeft: Long = msg.size,
                var deliverTime: Long = 0,
                var delivered: Boolean = false
            )

            val iMsgs = msgs.map { IntMessage(it) }

            fun recalcMessages(endIdx: Int, prevTime: Long, curTime: Long, prevMsgCount: Int, curMsgCount: Int) {
                val prevBand = totalBandwidth / Integer.max(1, prevMsgCount)
                val curBand = totalBandwidth / Integer.max(1, curMsgCount)
                for (i in 0 until endIdx) {
                    val msg = iMsgs[i]
                    if (!msg.delivered && msg.lastUpdateSizeLeft > 0) {
                        if (msg.msg.sendTime < curTime) {
                            val transmittedTillNowBytes = prevBand.getTransmitSize(curTime - prevTime)
                            msg.lastUpdateSizeLeft = max(0,
                                msg.lastUpdateSizeLeft - transmittedTillNowBytes)
                            msg.deliverTime = curTime + curBand.getTransmitTimeMillis(msg.lastUpdateSizeLeft)
                        } else {
                            msg.deliverTime = curTime + max(1, curBand.getTransmitTimeMillis(msg.lastUpdateSizeLeft))
                        }
                    }
                }
            }

            var curTime = msgs[0].sendTime
            var prevTime: Long
            var curMsgCount = 0


            for(i in msgs.indices) {
                val msg = iMsgs[i]

                while (true) {
                    val nearestDeliveryMsg = iMsgs
                        .take(i)
                        .filter { !it.delivered && it.deliverTime < msg.msg.sendTime }
                        .minByOrNull { it.deliverTime }
                        ?: break

                    prevTime = curTime
                    curTime = nearestDeliveryMsg.deliverTime
                    curMsgCount--
                    assert(curMsgCount >= 0)

                    nearestDeliveryMsg.delivered = true
                    recalcMessages(i, prevTime, curTime, curMsgCount + 1, curMsgCount)
                }

                prevTime = curTime
                curTime = msg.msg.sendTime
                curMsgCount++

                recalcMessages(i + 1, prevTime, curTime, curMsgCount - 1, curMsgCount)
            }

            while (true) {
                val nearestDeliveryMsg = iMsgs
                    .filter { !it.delivered }
                    .minByOrNull { it.deliverTime }
                    ?: break

                prevTime = curTime
                curTime = nearestDeliveryMsg.deliverTime
                curMsgCount--
                assert(curMsgCount >= 0)

                nearestDeliveryMsg.delivered = true
                recalcMessages(msgs.size, prevTime, curTime, curMsgCount + 1, curMsgCount)
            }

            return iMsgs.map { it.deliverTime }.toLongArray()
        }
    }
}

private fun <T : Comparable<T>> Collection<T>.isOrdered() =
    this
        .windowed(2) { l -> l[1] >= l[0]}
        .all { it }

fun BandwidthDelayer.logging(logger: (String) -> Unit) = LoggingDelayer(this, logger)

class LoggingDelayer(
    val delegate: BandwidthDelayer,
    val logger: (String) -> Unit
) : BandwidthDelayer {

    val counter = AtomicInteger(0)

    override val totalBandwidth = delegate.totalBandwidth

    override fun delay(size: Long): CompletableFuture<Unit> {
        val id = counter.getAndIncrement()
        logger("[$id] Started $size")
        return delegate.delay(size)
            .thenApply {
                logger("[$id] Completed $size")
            }
    }
}