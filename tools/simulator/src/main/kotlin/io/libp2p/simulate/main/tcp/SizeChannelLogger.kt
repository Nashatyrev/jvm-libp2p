package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.util.ReadableSize
import io.libp2p.tools.log
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class SizeChannelLogger(
    val name: String,
    val sizeExtractor: (Any) -> Long,
    val printPeriod: Duration = Duration.ZERO,
    val printFirstIndividualPackets: Int = 1
) : ChannelDuplexHandler() {

    var readBytes: Long = 0
    var readCount: Int = 0
    var writeBytes: Long = 0
    var writeCount: Int = 0
    var firstByteReadTime: Long = 0
    var lastReadTime = 0L
    var firstByteWriteTime: Long = 0
    var lastWrittenTime = 0L

    val executor = Executors.newSingleThreadScheduledExecutor()
    var printTask: ScheduledFuture<*>? = null
    var lastPrintedReadCount = 0
    var lastPrintedWriteCount = 0

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
        if (readCount > lastPrintedReadCount) {
            lastPrintedReadCount = readCount
            val totSize = ReadableSize.create(readBytes)
            val t = lastReadTime - firstByteReadTime
            log("[$name] Read total count: $readCount, size: $totSize in $t ms)")
        }

        if (writeCount > lastPrintedWriteCount) {
            lastPrintedWriteCount = writeCount
            val totSize = ReadableSize.create(writeBytes)
            val t = lastWrittenTime - firstByteWriteTime
            log("[$name] Write total count: $writeCount, size: $totSize in $t ms)")
        }
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val msgSize = sizeExtractor(msg)
        val curTime = System.currentTimeMillis()
        if (firstByteReadTime == 0L) firstByteReadTime = curTime
        lastReadTime = curTime
        val size = ReadableSize.create(msgSize)
        readBytes += msgSize
        readCount++
        val totSize = ReadableSize.create(readBytes)
        val t = curTime - firstByteReadTime

        if (printPeriod == Duration.ZERO || readCount <= printFirstIndividualPackets) {
            log("[$name] Read $size (count: $readCount, total: $totSize in $t ms)")
            lastPrintedReadCount = readCount
        } else {
            maybeStartPeriodicLog()
        }

        super.channelRead(ctx, msg)
    }

    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val msgSize = sizeExtractor(msg)
        val size = ReadableSize.create(msgSize)
        val curTime = System.currentTimeMillis()
        if (firstByteWriteTime == 0L) firstByteWriteTime = curTime
        writeBytes += msgSize
        writeCount++
        val totSize = ReadableSize.create(writeBytes)

        val printLog = printPeriod == Duration.ZERO || writeCount <= printFirstIndividualPackets
        if (printLog) {
            log("[$name] Write $size (count: $writeCount, total: $totSize)")
            lastPrintedWriteCount = writeCount
        } else {
            maybeStartPeriodicLog()
        }
        promise.addListener {
            lastWrittenTime = System.currentTimeMillis()
            if (printLog) {
                val t = lastWrittenTime - firstByteWriteTime
                log("[$name] Written $size (count: $writeCount, total: $totSize in $t ms)")
            }
        }
        super.write(ctx, msg, promise)
    }

    fun reset() {
        readBytes = 0
        readCount = 0
        lastPrintedReadCount = 0
        writeBytes = 0
        writeCount = 0
        lastPrintedWriteCount = 0
        firstByteReadTime = 0
        lastReadTime = 0
        firstByteWriteTime = 0
        lastWrittenTime = 0
        if (printTask != null) {
            printTask!!.cancel(true)
            printTask = null
        }

        log("[$name] Reset ===========")
    }
}