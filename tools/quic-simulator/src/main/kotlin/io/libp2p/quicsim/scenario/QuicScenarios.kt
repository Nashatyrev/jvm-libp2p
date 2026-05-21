package io.libp2p.quicsim.scenario

import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object QuicScenarios {
    fun slowStart(): QuicScenario<DataChunkNodeProgramFactory> {
        val nodeCount = 2
        return QuicScenario(
            name = "quic-slow-start",
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
                    )
                )
            }
        )
    }
}
