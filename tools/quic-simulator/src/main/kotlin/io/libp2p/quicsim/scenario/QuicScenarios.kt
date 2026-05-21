package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object QuicScenarios {
    const val SLOW_START = "quic-slow-start"

    fun slowStart(
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<DataChunkNodeProgramFactory> {
        val nodeCount = 2
        return QuicScenario(
            name = SLOW_START,
            network = QuicNetworkTopology.star(
                hostCount = nodeCount,
                latency = 100.milliseconds,
                bandwidthBytesPerSecond = 1_000_000L
            ),
            maxRunDuration = 100.seconds,
            createNodeProgramFactory = {
                DataChunkNodeProgramFactory(
                    nodeCount = nodeCount,
                    chunks = listOf(
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 1_000_000,
                            at = 10.seconds,
                            from = 0,
                            to = 1
                        ),
                        DataChunkNodeProgramFactory.DataChunk(
                            sizeBytes = 1_000_000,
                            at = 30.seconds,
                            from = 0,
                            to = 1
                        )
                    ),
                    eventSink = eventSink
                )
            }
        )
    }

    fun byName(
        name: String,
        eventSink: QuicScenarioEventSink = RecordingQuicScenarioEventSink()
    ): QuicScenario<DataChunkNodeProgramFactory> =
        when (name) {
            SLOW_START -> slowStart(eventSink)
            else -> throw IllegalArgumentException("Unknown QUIC scenario: $name")
        }
}
