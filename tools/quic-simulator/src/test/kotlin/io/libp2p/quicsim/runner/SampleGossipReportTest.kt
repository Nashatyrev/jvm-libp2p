package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.GossipMetrics
import io.libp2p.quicsim.runner.shadow.ShadowQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicScenarioResult
import io.libp2p.quicsim.scenario.QuicScenarios
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.math.sqrt
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
            result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                random = simulatorRandom()
            ).run(sampleGossip100LargeMessages()),
            outputPath = outputDir().resolve(
                "sample-gossip-128k-10ms-5pub${runLabelSuffix()}-simulated-message-receipts.csv"
            )
        )
    }

    @Test
    fun `write simulated sample gossip large message dissemination seed report`() {
        val runCount = System.getProperty("sampleGossip.seedRuns")?.toInt() ?: 10
        val seedBase = System.getProperty("sampleGossip.seedBase")?.toLong() ?: 10_000L
        val runs = (0 until runCount).map { runIndex ->
            val topologySeed = (seedBase + runIndex * 101).toInt()
            val gossipSeedBase = seedBase * 10 + runIndex * 1_001L
            val simulatorSeed = seedBase * 100 + runIndex * 10_001L
            val result = SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                random = seededSecureRandom(simulatorSeed)
            ).run(
                QuicScenarios.sampleGossip100LargeMessages(
                    topologySeed = topologySeed,
                    gossipSeedBase = gossipSeedBase
                )
            )
            val publications = GossipMetrics.messagePublications(result.events)
            val receipts = GossipMetrics.messageReceipts(result.events)
            val firstPublication = publications.minOf { it.publishedAt }
            val firstReceipt = receipts.minOf { it.receivedAt }
            val lastReceipt = receipts.maxOf { it.receivedAt }
            LargeMessageSeedRun(
                runIndex = runIndex,
                topologySeed = topologySeed,
                gossipSeedBase = gossipSeedBase,
                simulatorSeed = simulatorSeed,
                publicationCount = publications.size,
                receiptCount = receipts.size,
                firstPublication = firstPublication,
                firstReceipt = firstReceipt,
                lastReceipt = lastReceipt
            )
        }

        val reportDir = outputDir().also { it.createDirectories() }
        writeLargeMessageSeedRunsCsv(
            runs = runs,
            outputPath = reportDir.resolve("sample-gossip-128k-10ms-5pub-simulated-seed-runs.csv")
        )
        writeLargeMessageSeedRunsSummaryCsv(
            runs = runs,
            outputPath = reportDir.resolve("sample-gossip-128k-10ms-5pub-simulated-seed-summary.csv")
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

    private fun millis(duration: Duration): Double =
        duration.inWholeNanoseconds / 1_000_000.0

    private fun decimal(value: Double): String =
        "%.6f".format(Locale.US, value)

    private data class LargeMessageSeedRun(
        val runIndex: Int,
        val topologySeed: Int,
        val gossipSeedBase: Long,
        val simulatorSeed: Long,
        val publicationCount: Int,
        val receiptCount: Int,
        val firstPublication: Duration,
        val firstReceipt: Duration,
        val lastReceipt: Duration
    ) {
        val disseminationMs: Double
            get() = (lastReceipt - firstPublication).inWholeNanoseconds / 1_000_000.0

        val receiptSpanMs: Double
            get() = (lastReceipt - firstReceipt).inWholeNanoseconds / 1_000_000.0
    }

    private fun writeLargeMessageSeedRunsCsv(
        runs: List<LargeMessageSeedRun>,
        outputPath: Path
    ) {
        outputPath.writeText(
            buildString {
                appendLine(
                    "run_index,topology_seed,gossip_seed_base,simulator_seed,publication_count,receipt_count," +
                            "first_publication_s,first_receipt_s,last_receipt_s,dissemination_ms,receipt_span_ms"
                )
                runs.forEach { run ->
                    appendLine(
                        listOf(
                            run.runIndex,
                            run.topologySeed,
                            run.gossipSeedBase,
                            run.simulatorSeed,
                            run.publicationCount,
                            run.receiptCount,
                            seconds(run.firstPublication),
                            seconds(run.firstReceipt),
                            seconds(run.lastReceipt),
                            decimal(run.disseminationMs),
                            decimal(run.receiptSpanMs)
                        ).joinToString(",")
                    )
                }
            }
        )
        println("Wrote large-message seed run CSV: $outputPath")
    }

    private fun writeLargeMessageSeedRunsSummaryCsv(
        runs: List<LargeMessageSeedRun>,
        outputPath: Path
    ) {
        val dissemination = runs.map { it.disseminationMs }
        val receiptSpan = runs.map { it.receiptSpanMs }
        outputPath.writeText(
            buildString {
                appendLine("metric,value")
                appendLine("run_count,${runs.size}")
                appendLine("dissemination_min_ms,${decimal(dissemination.minOrNull() ?: 0.0)}")
                appendLine("dissemination_max_ms,${decimal(dissemination.maxOrNull() ?: 0.0)}")
                appendLine("dissemination_mean_ms,${decimal(dissemination.mean())}")
                appendLine("dissemination_stddev_ms,${decimal(dissemination.stddev())}")
                appendLine("receipt_span_min_ms,${decimal(receiptSpan.minOrNull() ?: 0.0)}")
                appendLine("receipt_span_max_ms,${decimal(receiptSpan.maxOrNull() ?: 0.0)}")
                appendLine("receipt_span_mean_ms,${decimal(receiptSpan.mean())}")
                appendLine("receipt_span_stddev_ms,${decimal(receiptSpan.stddev())}")
                appendLine("receipt_count_min,${runs.minOfOrNull { it.receiptCount } ?: 0}")
                appendLine("receipt_count_max,${runs.maxOfOrNull { it.receiptCount } ?: 0}")
            }
        )
        println("Wrote large-message seed summary CSV: $outputPath")
    }

    private fun List<Double>.mean(): Double =
        if (isEmpty()) 0.0 else sum() / size

    private fun List<Double>.stddev(): Double {
        if (size < 2) return 0.0
        val mean = mean()
        return sqrt(sumOf { (it - mean) * (it - mean) } / (size - 1))
    }

    private fun seededSecureRandom(seed: Long): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(seed.toString().toByteArray())
        }

    private fun outputDir(): Path =
        Path(System.getProperty("sampleGossipReport.dir", "build/reports/sample-gossip"))

    private fun shadowParallelism(): Int? =
        System.getProperty("shadow.parallelism")?.toInt()

    private fun sampleGossip100() =
        QuicScenarios.sampleGossip100(
            topologySeed = System.getProperty("sampleGossip.topologySeed")?.toInt() ?: 1234,
            gossipSeedBase = System.getProperty("sampleGossip.gossipSeedBase")?.toLong() ?: 0L
        )

    private fun sampleGossip100LargeMessages() =
        QuicScenarios.sampleGossip100LargeMessages(
            topologySeed = System.getProperty("sampleGossip.topologySeed")?.toInt() ?: 1234,
            gossipSeedBase = System.getProperty("sampleGossip.gossipSeedBase")?.toLong() ?: 0L
        )

    private fun simulatorRandom(): SecureRandom =
        System.getProperty("sampleGossip.simulatorSeed")?.toLong()
            ?.let(::seededSecureRandom)
            ?: SecureRandom(byteArrayOf(100))

    private fun runLabelSuffix(): String =
        System.getProperty("sampleGossip.runLabel")
            ?.takeIf { it.isNotBlank() }
            ?.let { "-$it" }
            .orEmpty()
}
