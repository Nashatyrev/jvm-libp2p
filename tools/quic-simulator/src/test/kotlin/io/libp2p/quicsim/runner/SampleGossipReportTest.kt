package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.GossipMetrics
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
import kotlin.time.Duration

class SampleGossipReportTest {
    @Test
    fun `write simulated sample gossip message receipt csv`() {
        val result = SimulatedQuicScenarioRunner(latencyWindowParallelism = 20).run(sampleGossip100())

        writeMessagePublicationCsv(
            result = result,
            outputPath = outputDir().resolve("sample-gossip-simulated-message-publications.csv")
        )
        writeMessageReceiptCsv(
            result = result,
            outputPath = outputDir().resolve("sample-gossip-simulated-message-receipts.csv")
        )
    }

    @Test
    fun `write shadow sample gossip message receipt csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        val result = ShadowQuicScenarioRunner(
            shadowPath = Path(shadowPath),
            workDir = Files.createTempDirectory("quic-shadow-sample-gossip-report-"),
            parallelism = shadowParallelism()
        ).run(sampleGossip100())

        writeMessagePublicationCsv(
            result = result,
            outputPath = outputDir().resolve("sample-gossip-shadow-message-publications.csv")
        )
        writeMessageReceiptCsv(
            result = result,
            outputPath = outputDir().resolve("sample-gossip-shadow-message-receipts.csv")
        )
    }

    @Test
    fun `write simulated sample gossip 20 sync publish message receipt csv`() {
        val result = SimulatedQuicScenarioRunner(latencyWindowParallelism = 20)
            .run(QuicScenarios.sampleGossip20SyncPublish())

        writeMessagePublicationCsv(
            result = result,
            outputPath = outputDir().resolve("sample-gossip-20-sync-publish-simulated-message-publications.csv")
        )
        writeMessageReceiptCsv(
            result = result,
            outputPath = outputDir().resolve("sample-gossip-20-sync-publish-simulated-message-receipts.csv")
        )
    }

    @Test
    fun `write shadow sample gossip 20 sync publish message receipt csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        val result = ShadowQuicScenarioRunner(
            shadowPath = Path(shadowPath),
            workDir = Files.createTempDirectory("quic-shadow-sample-gossip-20-sync-report-"),
            parallelism = shadowParallelism()
        ).run(QuicScenarios.sampleGossip20SyncPublish())

        writeMessagePublicationCsv(
            result = result,
            outputPath = outputDir().resolve("sample-gossip-20-sync-publish-shadow-message-publications.csv")
        )
        writeMessageReceiptCsv(
            result = result,
            outputPath = outputDir().resolve("sample-gossip-20-sync-publish-shadow-message-receipts.csv")
        )
    }

    @Test
    fun `write simulated sample gossip large message receipt csv`() {
        writeMessageReceiptCsv(
            result = SimulatedQuicScenarioRunner().run(QuicScenarios.sampleGossip100LargeMessages()),
            outputPath = outputDir().resolve("sample-gossip-128k-10ms-5pub-simulated-message-receipts.csv")
        )
    }

    @Test
    fun `write shadow sample gossip large message receipt csv`() {
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow report")

        writeMessageReceiptCsv(
            result = ShadowQuicScenarioRunner(
                shadowPath = Path(shadowPath),
                workDir = Files.createTempDirectory("quic-shadow-sample-gossip-large-report-"),
                parallelism = shadowParallelism()
            ).run(QuicScenarios.sampleGossip100LargeMessages()),
            outputPath = outputDir().resolve("sample-gossip-128k-10ms-5pub-shadow-message-receipts.csv")
        )
    }

    private fun writeMessageReceiptCsv(
        result: QuicScenarioResult<*>,
        outputPath: Path
    ) {
        outputPath.parent.createDirectories()
        val csvRows = buildString {
            appendLine("runner,scenario,time_ns,time_s,receiving_node_id,publishing_node_id")
            GossipMetrics.messageReceipts(result.events).forEach { receipt ->
                appendLine(
                    listOf(
                        result.runnerName,
                        result.scenarioName,
                        receipt.receivedAt.inWholeNanoseconds,
                        seconds(receipt.receivedAt),
                        receipt.receivingNodeId,
                        receipt.publishingNodeId
                    ).joinToString(",")
                )
            }
        }
        outputPath.writeText(csvRows)
        println("Wrote sample gossip message receipt CSV: $outputPath")
    }

    private fun writeMessagePublicationCsv(
        result: QuicScenarioResult<*>,
        outputPath: Path
    ) {
        outputPath.parent.createDirectories()
        val csvRows = buildString {
            appendLine("runner,scenario,time_ns,time_s,publishing_node_id")
            GossipMetrics.messagePublications(result.events).forEach { publication ->
                appendLine(
                    listOf(
                        result.runnerName,
                        result.scenarioName,
                        publication.publishedAt.inWholeNanoseconds,
                        seconds(publication.publishedAt),
                        publication.publishingNodeId
                    ).joinToString(",")
                )
            }
        }
        outputPath.writeText(csvRows)
        println("Wrote sample gossip message publication CSV: $outputPath")
    }

    private fun seconds(duration: Duration): String =
        "%.9f".format(duration.inWholeNanoseconds / 1_000_000_000.0)

    private fun outputDir(): Path =
        Path(System.getProperty("sampleGossipReport.dir", "build/reports/sample-gossip"))

    private fun shadowParallelism(): Int? =
        System.getProperty("shadow.parallelism")?.toInt()

    private fun sampleGossip100() =
        QuicScenarios.sampleGossip100(
            topologySeed = System.getProperty("sampleGossip.topologySeed")?.toInt() ?: 1234,
            gossipSeedBase = System.getProperty("sampleGossip.gossipSeedBase")?.toLong() ?: 0L
        )
}
