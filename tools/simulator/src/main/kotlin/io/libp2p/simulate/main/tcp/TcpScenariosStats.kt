package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.main.tcp.EventRecordingHandler.Event
import io.libp2p.simulate.main.tcp.EventRecordingHandler.EventType.*
import io.libp2p.simulate.main.tcp.TcpScenarios.RunParams
import io.libp2p.simulate.stats.ResultPrinter
import io.libp2p.simulate.util.InlineProperties
import io.libp2p.simulate.util.max
import io.libp2p.simulate.util.min
import io.libp2p.simulate.util.toMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

fun main() {
    TcpScenariosStats().validateWaves("work.dir/tcp.err.json")

    TcpScenariosStats()
        .printStats(
            listOf(
//                "work.dir/tcp.res.json",
//                "work.dir/tcp.res.idle_off.json",
//                    "work.dir/tcp.res.default.json"
                "work.dir/tcp.err.json"
            )
        )
}

class TcpScenariosStats {

    data class MessageStats(
        val firstRead: Long,
        val firstDelivery: Long,
        val avrgDelivery: Long,
        val lastDelivery: Long,
        val lastWrite: Long,
        val lastWritten: Long,
        val maxReadDelay: Long
    )

    private data class Link(
        val localPort: Int,
        val remotePort: Int
    )

    data class RunParamsWave(
        @InlineProperties
        val params: RunParams,
        val wave: Int
    ) : Comparable<RunParamsWave> {


        override fun compareTo(other: RunParamsWave): Int = comparator.compare(this, other)

        companion object {
            val comparator =
                compareBy<RunParamsWave> { it.params }
                    .thenBy { it.wave }
        }
    }

    private val Event.link get() = Link(localPort, remotePort)

    fun printStats(files: List<String>) {
        val events = files
            .flatMap { load(it).entries }
            .toMap()
            .toSortedMap()

        val resStats = calcAllStats(events)
        val filteredResStats = resStats
//            .filterKeys {
//                it.params.clientCount == 1
//                        && it.params.direction == TcpScenarios.Direction.Outbound
//                        && it.params.staggering == 0.0
//            }
        val resultPrinter = ResultPrinter(filteredResStats).apply {
            addPropertiesAsMetrics { it }
        }
        println(resultPrinter.printPretty())
        println()
        println(resultPrinter.printTabSeparated())
    }

    fun calcAllStats(runEvents: Map<RunParams, List<Event>>): Map<RunParamsWave,MessageStats> =
        runEvents.flatMap { (params, events) ->
            splitByWaves(events, params)
                .map { calcWaveStats(it) }
                .withIndex()
                .map { RunParamsWave(params, it.index) to it.value }
        }.toMap()

    fun validateWaves(file: String) {
        val events = load(file)
        events.forEach { (params, events) ->
            val waves = splitByWaves(events, params)
            val validStr = waves
                .map { validateWave(it, params) }
                .map { if (it) "-" else "!" }
                .joinToString("")
            println("$validStr $params, time: ${events.first().time}")
        }
    }

    fun calcWaveStats(messageWave: List<Event>): MessageStats {
        require(messageWave.isNotEmpty())
        require(messageWave[0].type == WRITE)

        val firstWriteTime = messageWave[0].time
        fun Event.delayFromStart() = time - firstWriteTime

        val linkReads = messageWave
            .filter { it.type == READ }
            .groupBy { it.link }
            .values

        val deliveries = linkReads
            .map { it.last().delayFromStart() }
        val maxDelayBetweenReads = linkReads
            .flatMap { reads ->
                reads.zipWithNext { e1, e2 -> e2.time - e1.time }
            }
            .maxOrNull() ?: 0

        return MessageStats(
            messageWave.find { it.type == READ }!!.delayFromStart(),
            deliveries.min(),
            deliveries.average().toLong(),
            deliveries.max(),
            messageWave.findLast { it.type == WRITE }!!.delayFromStart(),
            messageWave.findLast { it.type == WRITTEN }!!.delayFromStart(),
            maxDelayBetweenReads
        )
    }

    companion object {

        fun load(file: String): Map<RunParams, List<Event>> {
            File(file).useLines {
                val sIt = it.filter {
                    it.trim().isNotBlank()
                }.iterator()

                if(!sIt.hasNext()) {
                    return emptyMap()
                }

                val ret = LinkedHashMap<RunParams, List<Event>>()

                var s = sIt.next()
                while (sIt.hasNext()) {
                    val paramsJson = s.substringAfter("Params:")
                    require(paramsJson.length < s.length)
                    val params = Json.decodeFromString<RunParams>(paramsJson)
                    s = sIt.next()
                    val events = mutableListOf<Event>()
                    while (s.startsWith("Event:")) {
                        val eventJson = s.substringAfter("Event:")
                        val event = Json.decodeFromString<Event>(eventJson)
                        events += event
                        if (!sIt.hasNext()) break
                        s = sIt.next()
                    }
                    ret[params] = events
                }
                return ret
            }
        }

        fun validateWaves(allEvents: List<Event>, params: RunParams): Boolean {
            val waves = splitByWaves(allEvents, params)
            return waves.all { validateWave(it, params) }
        }

        fun validateWave(wave: List<Event>, params: RunParams): Boolean {
            if (wave[0].type != WRITE) return false
            val totalSize = (params.msgSize * params.clientCount).toLong()
            val ports = wave.flatMap { listOf(it.localPort, it.remotePort) }.distinct()
            return totalSize == wave.filter { it.type == WRITE }.sumOf { it.size }
                    && totalSize == wave.filter { it.type == WRITTEN }.sumOf { it.size }
                    && totalSize == wave.filter { it.type == READ }.sumOf { it.size }
                    && ports.size == params.clientCount + 1
        }


        fun splitByWaves(
            events: List<Event>,
            params: RunParams,
        ): List<List<Event>> {
            val ret = mutableListOf<List<Event>>()
            var curWave = mutableListOf<Event>()
            val readSize = (params.msgSize * params.clientCount).toLong()
            var curReadSize = 0L
            events.onEach { event ->
                curWave += event
                if (event.type == READ) {
                    curReadSize += event.size
                    if (curReadSize > readSize) {
                        throw IllegalArgumentException()
                    }
                    if (curReadSize == readSize) {
                        ret += curWave
                        curWave = mutableListOf()
                        curReadSize = 0
                    }
                }
            }
            return ret
        }
//
//        fun splitByWaves(
//            events: List<Event>,
//            waveThresholdMs: Long = 500
//        ): List<List<Event>> {
//
//            val durations = listOf(0L) +
//                    events.zipWithNext { e1, e2 -> e2.time - e1.time }
//            val waveIndices = durations.withIndex().filter { it.value >= waveThresholdMs }.map { it.index }
//            val waveRanges = (listOf(0) + waveIndices + listOf(events.size))
//                .zipWithNext { i1, i2 -> i1 until i2 }
//            return waveRanges.map {
//                events.subList(it.first, it.last + 1)
//            }
//        }
    }
}