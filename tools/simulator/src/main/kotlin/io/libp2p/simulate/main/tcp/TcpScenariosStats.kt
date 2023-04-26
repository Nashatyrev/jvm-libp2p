package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.main.tcp.EventRecordingHandler.Event
import io.libp2p.simulate.main.tcp.TcpScenarios.RunParams
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

fun main() {
    TcpScenariosStats.load("tcp.res.json")
}

class TcpScenariosStats {

    data class MessageStats(
        val firstRead: Long,
        val lastRead: Long
    )

    companion object {

        fun load(file: String): Map<RunParams, List<List<Event>>> {
            File(file).useLines {
                val sIt = it.filter {
                    it.trim().isNotBlank()
                }.iterator()

                require(sIt.hasNext()) { "File is empty" }

                val ret = LinkedHashMap<RunParams, List<List<Event>>>()



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
                    ret[params] = splitByWaves(events, 1000)
                }
                return ret
            }
        }

        fun splitByWaves(
            events: List<Event>,
            waveThresholdMs: Long = 500
        ): List<List<Event>> {

            val durations = listOf(0L) +
                    events.zipWithNext { e1, e2 -> e2.time - e1.time }
            val waveIndices = durations.withIndex().filter { it.value >= waveThresholdMs }.map { it.index }
            val waveRanges = (listOf(0) + waveIndices + listOf(events.size))
                .zipWithNext { i1, i2 -> i1 until i2 }
            return waveRanges.map {
                events.subList(it.first, it.last - 1)
            }
        }
    }
}