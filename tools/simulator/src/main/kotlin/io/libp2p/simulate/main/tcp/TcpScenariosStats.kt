package io.libp2p.simulate.main.tcp

import io.libp2p.simulate.main.tcp.EventRecordingHandler.Event
import io.libp2p.simulate.main.tcp.TcpScenarios.RunParams
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

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
                    while (sIt.hasNext() && s.startsWith("Event:")) {
                        val eventJson = s.substringAfter("Event:")
                        val event = Json.decodeFromString<Event>(eventJson)
                        events += event
                        s = sIt.next()
                    }
                    ret[params] = TcpScenarios.splitByWaves(events)
                }
                return ret
            }
        }
    }
}