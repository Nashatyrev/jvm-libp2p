package io.libp2p.quicsim.runner.shadow

import io.libp2p.quicsim.program.DataChunkMetrics
import io.libp2p.quicsim.scenario.QuicScenarios
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.nanoseconds

class ShadowQuicScenarioRunnerTest {
    @Test
    fun `runs slow start scenario under shadow`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow integration test")

        val result = ShadowQuicScenarioRunner(
            shadowPath = Path(shadowPath),
            workDir = Files.createTempDirectory("quic-shadow-test-")
        ).run(QuicScenarios.slowStart())

        assertThat(DataChunkMetrics.chunkSends(result.events)).hasSize(2)
        assertThat(DataChunkMetrics.packetReceipts(result.events)).isNotEmpty
    }

    @Test
    fun `prints slow start scenario report under shadow`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow integration test")

        val result = ShadowQuicScenarioRunner(
            shadowPath = Path(shadowPath),
            workDir = Files.createTempDirectory("quic-shadow-report-")
        ).run(QuicScenarios.slowStart())

        val sends = DataChunkMetrics.chunkSends(result.events)
        val receipts = DataChunkMetrics.packetReceipts(result.events)

        println("SHADOW_SLOW_START_RESULT_BEGIN")
        println("runner=${result.runnerName}")
        println("scenario=${result.scenarioName}")
        println("events=${result.events.size}")
        println("chunkSends=${sends.size}")
        println("packetReceipts=${receipts.size}")
        sends.sortedBy { it.chunkIndex }.forEach { send ->
            println("send chunk=${send.chunkIndex} at=${send.sentAt}")
        }
        receipts.groupBy { it.chunkIndex }.toSortedMap().forEach { (chunkIndex, chunkReceipts) ->
            val sorted = chunkReceipts.sortedBy { it.receivedAt }
            val first = sorted.first().receivedAt
            val last = sorted.last().receivedAt
            val bytes = sorted.sumOf { it.payloadBytes }
            val span = last - first
            val throughputBytesPerSecond = if (span.isPositive()) {
                bytes * 1_000_000_000.0 / span.inWholeNanoseconds
            } else {
                Double.POSITIVE_INFINITY
            }
            println(
                "receipt chunk=$chunkIndex packets=${sorted.size} bytes=$bytes " +
                    "first=$first last=$last span=${span.inWholeNanoseconds.nanoseconds} " +
                    "throughputBytesPerSecond=$throughputBytesPerSecond"
            )
        }
        println("SHADOW_SLOW_START_RESULT_END")
    }
}
