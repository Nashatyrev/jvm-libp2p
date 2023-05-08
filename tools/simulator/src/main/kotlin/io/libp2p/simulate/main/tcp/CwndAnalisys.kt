package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.util.*
import io.libp2p.simulate.main.tcp.EventRecordingHandler.Event
import io.libp2p.simulate.main.tcp.EventRecordingHandler.EventType.READ
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    val runResults = TcpScenariosStats.load("work.dir/tcp.res.json")
    runResults.forEach { (params, events) ->
        println("$params")
        TcpScenariosStats.splitByWaves(events, params).forEach { waveEvents ->
            println("  Wave:")
            val cwnd = CwndAnalisys(
                params.halfPing.milliseconds,
                thresholdInterval = 20
            )
            val byChannels = cwnd.splitByReadChannels(waveEvents)
            byChannels.forEach { (ch, chEvents) ->
                println("    $ch")
                val readSeries = cwnd.getReadSeries(chEvents)
                readSeries.readSeriesWithGaps.forEach { (series, gap) ->
                    println("      ${gap}ms\t${series.tcpSegmentCount}segs\t${series.bandwidth}")
                }
            }
        }
    }
}

class CwndAnalisys(
    val halfPing: Duration,
    val tcpMSS: Int = 1460,
    val thresholdInterval: Long = halfPing.inWholeMilliseconds
) {


    data class SimplexChannel(
        val localPort: Int,
        val remotePort: Int
    ) {
        override fun toString() = "$localPort=>$remotePort"
    }

    inner class ReadSeries(
        val reads: List<Event>
    ) {
        val readCount = reads.size
        val size = reads.sumOf { it.size }
        val tcpSegmentCount = reads.sumOf { sizeToTcpSegments(it.size) }
        val startTime = reads.first().time
        val endTime = reads.last().time
        val timePeriod = (endTime - startTime).milliseconds
        val bandwidth = Bandwidth.fromSize(size, timePeriod)
    }

    inner class MessageSeries(
        val readSeries: List<ReadSeries>
    ) {
        val readSeriesGaps = listOf(0L) +
                readSeries.zipWithNext { s1, s2 -> s2.startTime - s1.endTime }
        val readSeriesWithGaps = readSeries.zip(readSeriesGaps)
    }

    fun sizeToTcpSegments(dataSize: Long): Int = (dataSize.toDouble() / tcpMSS).roundToInt()

    fun splitByReadChannels(wave: List<Event>): Map<SimplexChannel, List<Event>> =
        wave
            .filter { it.type == READ }
            .groupBy { SimplexChannel(it.localPort, it.remotePort) }

    fun getReadSeries(channelEvents: List<Event>): MessageSeries {
        val readEvents = channelEvents
            .filter { it.type == READ }
        val readIntervals = listOf(0L) +
                readEvents
                    .map { it.time }
                    .zipWithNext { t1, t2 -> t2 - t1 }

        val readIntervalsIdx = readIntervals.withIndex()
        val longReadIntervalIndexes = readIntervalsIdx
            .filter { it.value > thresholdInterval }
            .map { it.index }

        val seriesRanges = (listOf(0) + longReadIntervalIndexes + listOf(readEvents.size))
            .zipWithNext { f, l -> f until l }
        return seriesRanges
            .map { ReadSeries(readEvents[it]) }
            .let { MessageSeries(it) }
    }
}