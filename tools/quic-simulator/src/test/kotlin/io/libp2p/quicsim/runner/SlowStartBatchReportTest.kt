package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.DataChunkMetrics
import io.libp2p.quicsim.runner.shadow.ShadowQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicScenarioResult
import io.libp2p.quicsim.scenario.QuicScenarios
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SlowStartBatchReportTest {
    @Test
    fun `write simulated slow start packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(latencyWindowParallelism = 20).run(QuicScenarios.slowStart()),
            outputPath = outputDir().resolve("slow-start-simulated-batches.csv")
        )
    }

    @Test
    fun `write simulated slow start codel packet batch csv`() {
        writeBatchCsv(
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.CODEL
            ).run(QuicScenarios.slowStart()),
            outputPath = outputDir().resolve("slow-start-codel-simulated-batches.csv")
        )
    }

    @Test
    fun `write shadow slow start packet batch csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        writeBatchCsv(
            result = ShadowQuicScenarioRunner(
                shadowPath = Path(shadowPath),
                workDir = Files.createTempDirectory("quic-shadow-batch-report-")
            ).run(QuicScenarios.slowStart()),
            outputPath = outputDir().resolve("slow-start-shadow-batches.csv")
        )
    }

    private fun writeBatchCsv(
        result: QuicScenarioResult<*>,
        outputPath: Path
    ) {
        outputPath.parent.createDirectories()
        val rows = buildString {
            appendLine("runner,scenario,chunk,batch,start_time_ns,start_time_s,end_time_ns,end_time_s,packet_count,payload_bytes")
            DataChunkMetrics.packetBatches(result.events).forEach { batch ->
                appendLine(
                    listOf(
                        result.runnerName,
                        result.scenarioName,
                        batch.chunkIndex,
                        batch.batchIndex,
                        batch.startAt.inWholeNanoseconds,
                        "%.9f".format(batch.startAt.inWholeNanoseconds / 1_000_000_000.0),
                        batch.endAt.inWholeNanoseconds,
                        "%.9f".format(batch.endAt.inWholeNanoseconds / 1_000_000_000.0),
                        batch.packetCount,
                        batch.payloadBytes
                    ).joinToString(",")
                )
            }
        }
        outputPath.writeText(rows)
        println("Wrote packet batch CSV: $outputPath")
    }

    private fun outputDir(): Path =
        Path(System.getProperty("slowStartReport.dir", "build/reports/slow-start-batches"))
}
