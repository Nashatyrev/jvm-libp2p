package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.GossipMetrics
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.runner.shadow.ShadowQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicScenario
import io.libp2p.quicsim.scenario.QuicScenarioResult
import io.libp2p.quicsim.scenario.QuicScenarios
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class SimVsShadowGossipScenarioComparisonTest {
    @Test
    fun `sample gossip 100 simulated delivery count matches stored shadow run`() {
        assertGossipScenarioMatchesShadow(
            scenarioName = QuicScenarios.SAMPLE_GOSSIP_100,
            fixtureFileName = "${QuicScenarios.SAMPLE_GOSSIP_100}-shadow-message-receipts.csv",
            createScenario = { QuicScenarios.sampleGossip100() },
            maxMeanAbsoluteDifferenceRatio = 0.03,
            transferStart = 30.seconds
        )
    }

    @Test
    fun `sample gossip 20 sync publish simulated delivery count matches stored shadow run`() {
        assertGossipScenarioMatchesShadow(
            scenarioName = QuicScenarios.SAMPLE_GOSSIP_20_SYNC_PUBLISH,
            fixtureFileName = "${QuicScenarios.SAMPLE_GOSSIP_20_SYNC_PUBLISH}-shadow-message-receipts.csv",
            createScenario = { QuicScenarios.sampleGossip20SyncPublish() },
            maxMeanAbsoluteDifferenceRatio = 0.03,
            transferStart = 30.seconds
        )
    }

    @Test
    fun `sample gossip 100 large messages simulated delivery count matches stored shadow run`() {
        assertGossipScenarioMatchesShadow(
            scenarioName = QuicScenarios.SAMPLE_GOSSIP_100_128K_10MS_5_PUBLISHERS,
            fixtureFileName = "${QuicScenarios.SAMPLE_GOSSIP_100_128K_10MS_5_PUBLISHERS}-shadow-message-receipts.csv",
            createScenario = { QuicScenarios.sampleGossip100LargeMessages() },
            maxMeanAbsoluteDifferenceRatio = 0.03,
            transferStart = 30.seconds
        )
    }

    @Test
    fun `write shadow gossip scenario delivery count fixtures`() {
        assumeTrue(
            System.getProperty("simVsShadow.updateGossipFixtures").toBoolean(),
            "Set -DsimVsShadow.updateGossipFixtures=true to refresh stored Shadow gossip fixtures"
        )
        val shadowPath = System.getProperty("shadow.path")
        assumeTrue(!shadowPath.isNullOrBlank(), "Set -Dshadow.path=/path/to/shadow to run Shadow fixture refresh")

        val outputDir = Path(
            System.getProperty(
                "simVsShadow.fixture.dir",
                "src/test/resources/io/libp2p/quicsim/runner"
            )
        )
        outputDir.createDirectories()
        shadowFixtureScenarios().forEach { (scenarioName, fixtureFileName, createScenario) ->
            val result = ShadowQuicScenarioRunner(
                shadowPath = Path(shadowPath),
                workDir = Files.createTempDirectory("quic-shadow-$scenarioName-gossip-comparison-"),
                parallelism = shadowParallelism()
            ).run(createScenario())

            writeMessageReceiptCsv(
                result = result,
                outputPath = outputDir.resolve(fixtureFileName)
            )
        }
    }

    private fun assertGossipScenarioMatchesShadow(
        scenarioName: String,
        fixtureFileName: String,
        createScenario: () -> QuicScenario<NodeProgramFactory>,
        maxMeanAbsoluteDifferenceRatio: Double,
        transferStart: Duration = 0.seconds
    ) {
        val expected = readMessageReceiptCsv(
            resourceLines("/io/libp2p/quicsim/runner/$fixtureFileName")
        )
        val actual = messageReceipts(
            SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 32,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.SHADOW_LIKE,
                random = seededSecureRandom(scenarioName)
            ).run(createScenario())
        )
        val comparison = compareDeliveryCountCurve(
            expected = expected,
            actual = actual,
            transferStart = transferStart,
            maxMeanAbsoluteDifferenceRatio = maxMeanAbsoluteDifferenceRatio
        )

        val stats = GossipComparisonStats(scenarioName, comparison)
        recordStats(stats)
        println(stats.toReportLine())

        assertEquals(
            comparison.expectedReceiptCount,
            comparison.actualReceiptCount,
            "$scenarioName: expected sim to deliver the same number of gossip messages as the stored Shadow run"
        )
        assertTrue(
            comparison.meanAbsoluteDifference <= comparison.maxAllowedDifference,
            stats.toFailureMessage()
        )
    }

    private fun messageReceipts(result: QuicScenarioResult<*>): List<GossipMetrics.MessageReceipt> =
        GossipMetrics.messageReceipts(result.events)

    private fun resourceLines(path: String): List<String> =
        javaClass.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readLines() }
            ?: throw IllegalArgumentException("Missing test resource: $path")

    private fun readMessageReceiptCsv(lines: List<String>): List<GossipMetrics.MessageReceipt> {
        if (lines.isEmpty()) return emptyList()

        val columns = lines.first().split(',').mapIndexed { index, name -> name to index }.toMap()
        fun column(name: String): Int =
            columns[name] ?: throw IllegalArgumentException("CSV does not contain column $name")

        val timeNs = column("time_ns")
        val receivingNodeId = column("receiving_node_id")
        val publishingNodeId = column("publishing_node_id")

        return lines.drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(',')
                GossipMetrics.MessageReceipt(
                    receivedAt = parts[timeNs].toLong().nanoseconds,
                    receivingNodeId = parts[receivingNodeId].toInt(),
                    publishingNodeId = parts[publishingNodeId].toInt()
                )
            }
            .sortedWith(compareBy({ it.receivedAt }, { it.receivingNodeId }, { it.publishingNodeId }))
    }

    private fun seededSecureRandom(seed: String): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(seed.toByteArray())
        }

    private fun writeMessageReceiptCsv(
        result: QuicScenarioResult<*>,
        outputPath: Path
    ) {
        outputPath.writeText(
            buildString {
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
        )
        println("Wrote Shadow gossip delivery fixture: $outputPath")
    }

    private fun shadowFixtureScenarios(): List<Triple<String, String, () -> QuicScenario<NodeProgramFactory>>> =
        listOf(
            Triple(
                QuicScenarios.SAMPLE_GOSSIP_100,
                "${QuicScenarios.SAMPLE_GOSSIP_100}-shadow-message-receipts.csv",
                { QuicScenarios.sampleGossip100() }
            ),
            Triple(
                QuicScenarios.SAMPLE_GOSSIP_20_SYNC_PUBLISH,
                "${QuicScenarios.SAMPLE_GOSSIP_20_SYNC_PUBLISH}-shadow-message-receipts.csv",
                { QuicScenarios.sampleGossip20SyncPublish() }
            ),
            Triple(
                QuicScenarios.SAMPLE_GOSSIP_100_128K_10MS_5_PUBLISHERS,
                "${QuicScenarios.SAMPLE_GOSSIP_100_128K_10MS_5_PUBLISHERS}-shadow-message-receipts.csv",
                { QuicScenarios.sampleGossip100LargeMessages() }
            )
        )

    private fun compareDeliveryCountCurve(
        expected: List<GossipMetrics.MessageReceipt>,
        actual: List<GossipMetrics.MessageReceipt>,
        transferStart: Duration,
        maxMeanAbsoluteDifferenceRatio: Double
    ): GossipDeliveryCountComparison {
        val expectedTimes = expected
            .map { it.receivedAt }
            .filter { it >= transferStart }
            .sorted()
        val actualTimes = actual
            .map { it.receivedAt }
            .filter { it >= transferStart }
            .sorted()
        val expectedCompleteAt = expectedTimes.lastOrNull() ?: transferStart
        val actualCompleteAt = actualTimes.lastOrNull() ?: transferStart
        val expectedTransferDuration = expectedCompleteAt - transferStart
        val times = (listOf(transferStart) + expectedTimes + actualTimes).distinct().sorted()

        var expectedDeliveredCount = 0
        var actualDeliveredCount = 0
        var expectedIndex = 0
        var actualIndex = 0
        var previousTime = times.firstOrNull() ?: transferStart
        var absoluteCountNanos = 0.0

        times.forEach { time ->
            absoluteCountNanos += abs(expectedDeliveredCount - actualDeliveredCount).toDouble() *
                (time - previousTime).inWholeNanoseconds
            while (expectedIndex < expectedTimes.size && expectedTimes[expectedIndex] == time) {
                expectedDeliveredCount++
                expectedIndex++
            }
            while (actualIndex < actualTimes.size && actualTimes[actualIndex] == time) {
                actualDeliveredCount++
                actualIndex++
            }
            previousTime = time
        }

        val meanAbsoluteDifference = (
            absoluteCountNanos / expectedTimes.size.coerceAtLeast(1)
            ).toLong().nanoseconds
        val maxAllowedDifference = (
            expectedTransferDuration.inWholeNanoseconds * maxMeanAbsoluteDifferenceRatio
            ).toLong().nanoseconds
        return GossipDeliveryCountComparison(
            expectedReceiptCount = expectedTimes.size,
            actualReceiptCount = actualTimes.size,
            expectedTransferDuration = expectedTransferDuration,
            meanAbsoluteDifference = meanAbsoluteDifference,
            maxAllowedDifference = maxAllowedDifference,
            expectedCompleteAt = expectedCompleteAt,
            actualCompleteAt = actualCompleteAt
        )
    }

    private data class GossipDeliveryCountComparison(
        val expectedReceiptCount: Int,
        val actualReceiptCount: Int,
        val expectedTransferDuration: Duration,
        val meanAbsoluteDifference: Duration,
        val maxAllowedDifference: Duration,
        val expectedCompleteAt: Duration,
        val actualCompleteAt: Duration
    ) {
        val completionDelta: Duration
            get() = actualCompleteAt - expectedCompleteAt

        val normalizedDifferencePercent: Double
            get() = meanAbsoluteDifference.inWholeNanoseconds.toDouble() /
                expectedTransferDuration.inWholeNanoseconds.coerceAtLeast(1L) *
                100.0

        val maxAllowedDifferencePercent: Double
            get() = maxAllowedDifference.inWholeNanoseconds.toDouble() /
                expectedTransferDuration.inWholeNanoseconds.coerceAtLeast(1L) *
                100.0
    }

    private data class GossipComparisonStats(
        val scenarioName: String,
        val comparison: GossipDeliveryCountComparison
    ) {
        fun toReportLine(): String =
            "Sim-vs-Shadow gossip delivery [$scenarioName]: " +
                "meanAbs=${comparison.meanAbsoluteDifference.toMillisString()} " +
                "max=${comparison.maxAllowedDifference.toMillisString()} " +
                "normalized=${comparison.normalizedDifferencePercent.formatPercent()}% " +
                "maxNormalized=${comparison.maxAllowedDifferencePercent.formatPercent()}% " +
                "completionDelta=${comparison.completionDelta.toMillisString()} " +
                "shadowDuration=${comparison.expectedTransferDuration.toMillisString()} " +
                "receipts=${comparison.actualReceiptCount}/${comparison.expectedReceiptCount}"

        fun toFailureMessage(): String =
            "$scenarioName gossip delivery count curve mean absolute difference " +
                "${comparison.meanAbsoluteDifference.toMillisString()} " +
                "(${comparison.normalizedDifferencePercent.formatPercent()}% of Shadow transfer time) " +
                "exceeds ${comparison.maxAllowedDifference.toMillisString()}; " +
                "maxNormalized=${comparison.maxAllowedDifferencePercent.formatPercent()}% " +
                "completionDelta=${comparison.completionDelta.toMillisString()} " +
                "expectedReceiptCount=${comparison.expectedReceiptCount} " +
                "actualReceiptCount=${comparison.actualReceiptCount} " +
                "shadowTransferDuration=${comparison.expectedTransferDuration} " +
                "shadowCompleteAt=${comparison.expectedCompleteAt} " +
                "actualCompleteAt=${comparison.actualCompleteAt}"

        private fun Duration.toMillisString(): String =
            "%.3fms".format(inWholeNanoseconds / 1_000_000.0)

        private fun Double.formatPercent(): String =
            "%.3f".format(this)
    }

    private fun seconds(duration: Duration): String =
        "%.9f".format(duration.inWholeNanoseconds / 1_000_000_000.0)

    private fun shadowParallelism(): Int? =
        System.getProperty("shadow.parallelism")?.toInt()

    companion object {
        private val comparisonStats = mutableListOf<GossipComparisonStats>()

        private fun recordStats(stats: GossipComparisonStats) {
            synchronized(comparisonStats) {
                comparisonStats += stats
            }
        }

        @AfterAll
        @JvmStatic
        fun printComparisonStats() {
            if (comparisonStats.isEmpty()) {
                return
            }
            println("Sim-vs-Shadow gossip delivery summary:")
            comparisonStats
                .sortedBy { it.scenarioName }
                .forEach { println(it.toReportLine()) }
        }
    }
}
