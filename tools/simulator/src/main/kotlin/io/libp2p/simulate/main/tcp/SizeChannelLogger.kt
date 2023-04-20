package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.util.ReadableSize
import io.libp2p.tools.log
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class SizeChannelLogger(
    val name: String,
    val sizeExtractor: (Any) -> Long,
    val printPeriod: Duration = Duration.ZERO,
    val printFirstIndividualPackets: Int = 1
) : ChannelDuplexHandler() {

    data class Counters(
        var readBytes: Long = 0,
        var readCount: Int = 0,
        var writeBytes: Long = 0,
        var writeCount: Int = 0,
        var writtenBytes: Long = 0,
        var writtenCount: Int = 0,
        var firstByteReadTime: Long = 0,
        var lastReadTime: Long = 0L,
        var firstByteWriteTime: Long = 0,
        var lastWrittenTime: Long = 0L,
        var lastPrintedReadCount: Int = 0,
        var lastPrintedWriteCount: Int = 0,
    )

    var counts = Counters()

    val executor = Executors.newSingleThreadScheduledExecutor()
    var printTask: ScheduledFuture<*>? = null

    fun maybeStartPeriodicLog() {
        if (printPeriod > Duration.ZERO && printTask == null) {
            printTask = executor.scheduleAtFixedRate(
                ::printPeriodicStat,
                printPeriod.inWholeMilliseconds,
                printPeriod.inWholeMilliseconds,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun printPeriodicStat() {
        if (counts.readCount > counts.lastPrintedReadCount) {
            counts.lastPrintedReadCount = counts.readCount
            logRead("TOTAL")
        }

        if (counts.writtenCount > counts.lastPrintedWriteCount) {
            counts.lastPrintedWriteCount = counts.writtenCount
            logWritten("TOTAL")
        }
    }

    fun logRead(packetString: Any) {
        val totSize = ReadableSize.create(counts.readBytes)
        val t = counts.lastReadTime - counts.firstByteReadTime
        val throughput = when(t) {
            0L -> ""
            else -> Bandwidth.fromSize(counts.readBytes, t.milliseconds)
        }
        log("[$name] Read $packetString count: ${counts.readCount}, size: $totSize in $t ms @ $throughput)")
    }

    fun logWritten(packetString: Any) {
        val totSize = ReadableSize.create(counts.writtenBytes)
        val t = counts.lastWrittenTime - counts.firstByteWriteTime
        val throughput = when(t) {
            0L -> ""
            else -> Bandwidth.fromSize(counts.writtenBytes, t.milliseconds)
        }
        log("[$name] Written $packetString count: ${counts.writtenCount}, size: $totSize in $t ms @ $throughput)")
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val msgSize = sizeExtractor(msg)
        val curTime = System.currentTimeMillis()
        if (counts.firstByteReadTime == 0L) counts.firstByteReadTime = curTime
        counts.lastReadTime = curTime
        counts.readBytes += msgSize
        counts.readCount++

        if (printPeriod == Duration.ZERO || counts.readCount <= printFirstIndividualPackets) {
            logRead(ReadableSize.create(msgSize))
            counts.lastPrintedReadCount = counts.readCount
        } else {
            maybeStartPeriodicLog()
        }

        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val msgSize = sizeExtractor(msg)
        val size = ReadableSize.create(msgSize)
        val curTime = System.currentTimeMillis()
        if (counts.firstByteWriteTime == 0L) counts.firstByteWriteTime = curTime
        counts.writeBytes += msgSize
        counts.writeCount++

        val printLog = printPeriod == Duration.ZERO || counts.writtenCount <= printFirstIndividualPackets
        if (printLog) {
            val totSize = ReadableSize.create(counts.writeBytes)
            log("[$name] Write $size (count: ${counts.writeCount}, total: $totSize)")
            counts.lastPrintedWriteCount = counts.writtenCount
        } else {
            maybeStartPeriodicLog()
        }
        promise.addListener {
            counts.writtenBytes += msgSize
            counts.writtenCount++
            counts.lastWrittenTime = System.currentTimeMillis()
            if (printLog) {
                logWritten(size)
            }
        }
        super.write(ctx, msg, promise)
    }

    fun reset() {
        counts = Counters()
        if (printTask != null) {
            printTask!!.cancel(true)
            printTask = null
        }

        log("[$name] Reset ===========")
    }
}