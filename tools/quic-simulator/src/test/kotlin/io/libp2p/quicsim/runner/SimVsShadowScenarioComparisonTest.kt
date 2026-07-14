package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.DataChunkMetrics
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

class SimVsShadowScenarioComparisonTest {
    @Test
    fun `slow start simulated app delivery matches stored shadow run`() {
        assertScenarioMatchesShadow(
            scenarioName = QuicScenarios.SLOW_START,
            fixtureFileName = "${QuicScenarios.SLOW_START}-shadow-packet-receipts.csv",
            createScenario = { QuicScenarios.slowStart() },
            maxMeanAbsoluteDifferenceRatio = 0.01
        )
    }

    @Test
    fun `single transfer 8mb simulated app delivery matches stored shadow run`() {
        assertScenarioMatchesShadow(
            scenarioName = QuicScenarios.SINGLE_TRANSFER_8MB,
            fixtureFileName = "${QuicScenarios.SINGLE_TRANSFER_8MB}-shadow-packet-receipts.csv",
            createScenario = { QuicScenarios.singleTransfer8Mb() },
            maxMeanAbsoluteDifferenceRatio = 0.01
        )
    }

    @Test
    fun `single transfer 8mb half receiver bandwidth simulated app delivery matches stored shadow run`() {
        assertScenarioMatchesShadow(
            scenarioName = QuicScenarios.SINGLE_TRANSFER_8MB_HALF_RECEIVER_BW,
            fixtureFileName = "${QuicScenarios.SINGLE_TRANSFER_8MB_HALF_RECEIVER_BW}-shadow-packet-receipts.csv",
            createScenario = { QuicScenarios.singleTransfer8MbHalfReceiverBandwidth() },
            maxMeanAbsoluteDifferenceRatio = 0.01
        )
    }

    @Test
    fun `inbound congestion simulated app delivery matches stored shadow run`() {
        assertScenarioMatchesShadow(
            scenarioName = QuicScenarios.INBOUND_CONGESTION,
            fixtureFileName = "${QuicScenarios.INBOUND_CONGESTION}-shadow-packet-receipts.csv",
            createScenario = { QuicScenarios.inboundCongestion() },
            // TODO 3% is pretty large deviation. The simulator is quite flaky here:
            // normally it resides below 1%, but sometimes yields > 2%
            maxMeanAbsoluteDifferenceRatio = 0.03
        )
    }

    @Test
    fun `outbound congestion simulated app delivery matches stored shadow run`() {
        assertScenarioMatchesShadow(
            scenarioName = QuicScenarios.OUTBOUND_CONGESTION,
            fixtureFileName = "${QuicScenarios.OUTBOUND_CONGESTION}-shadow-packet-receipts.csv",
            createScenario = { QuicScenarios.outboundCongestion() },
            maxMeanAbsoluteDifferenceRatio = 0.01
        )
    }

    @Test
    fun `write shadow scenario app delivery fixtures`() {
        assumeTrue(
            System.getProperty("simVsShadow.updateFixtures").toBoolean(),
            "Set -DsimVsShadow.updateFixtures=true to refresh stored Shadow fixtures"
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
                workDir = Files.createTempDirectory("quic-shadow-$scenarioName-comparison-")
            ).run(createScenario())

            writePacketReceiptCsv(
                result = result,
                outputPath = outputDir.resolve(fixtureFileName)
            )
        }
    }

    private fun assertScenarioMatchesShadow(
        scenarioName: String,
        fixtureFileName: String,
        createScenario: () -> QuicScenario<NodeProgramFactory>,
        maxMeanAbsoluteDifferenceRatio: Double,
        transferStart: Duration = 10.seconds
    ) {
        val expected = readPacketReceiptCsv(
            resourceLines("/io/libp2p/quicsim/runner/$fixtureFileName")
        )
        val actual = appDeliveryEvents(
            SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 32,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.SHADOW_LIKE,
                random = seededSecureRandom(scenarioName)
            ).run(createScenario())
        )
        val comparison = compareAppDeliveryCurve(
            expected = expected,
            actual = actual,
            transferStart = transferStart,
            maxMeanAbsoluteDifferenceRatio = maxMeanAbsoluteDifferenceRatio
        )

        val stats = ComparisonStats(scenarioName, comparison)
        recordStats(stats)
        println(stats.toReportLine())

        assertEquals(
            comparison.expectedBytes,
            comparison.actualBytes,
            "$scenarioName: expected sim to deliver the same amount of app data as the stored Shadow run"
        )
        assertTrue(
            comparison.meanAbsoluteDifference <= comparison.maxAllowedDifference,
            stats.toFailureMessage()
        )
    }

    private fun appDeliveryEvents(result: QuicScenarioResult<*>): List<AppDeliveryEvent> =
        DataChunkMetrics.packetReceipts(result.events)
            .map {
                AppDeliveryEvent(
                    receivedAt = it.receivedAt,
                    payloadBytes = it.payloadBytes
                )
            }

    private fun resourceLines(path: String): List<String> =
        javaClass.getResourceAsStream(path)
            ?.bufferedReader()
            ?.use { it.readLines() }
            ?: throw IllegalArgumentException("Missing test resource: $path")

    private fun readPacketReceiptCsv(lines: List<String>): List<AppDeliveryEvent> =
        lines
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(",")
                AppDeliveryEvent(
                    receivedAt = parts[7].toLong().nanoseconds,
                    payloadBytes = parts[9].toInt()
                )
            }

    private fun seededSecureRandom(seed: String): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply {
            setSeed(seed.toByteArray())
        }

    private fun writePacketReceiptCsv(
        result: QuicScenarioResult<*>,
        outputPath: Path
    ) {
        val rows = buildString {
            appendLine("runner,scenario,chunk,sequence,total_packets,from,to,received_time_ns,received_time_s,payload_bytes")
            DataChunkMetrics.packetReceipts(result.events)
                .sortedWith(
                    compareBy(
                        { it.receivedAt },
                        { it.chunkIndex },
                        { it.sequence },
                        { it.from },
                        { it.to }
                    )
                )
                .forEach { receipt ->
                    appendLine(
                        listOf(
                            result.runnerName,
                            result.scenarioName,
                            receipt.chunkIndex,
                            receipt.sequence,
                            receipt.totalPackets,
                            receipt.from,
                            receipt.to,
                            receipt.receivedAt.inWholeNanoseconds,
                            "%.9f".format(receipt.receivedAt.inWholeNanoseconds / 1_000_000_000.0),
                            receipt.payloadBytes
                        ).joinToString(",")
                    )
                }
        }
        outputPath.writeText(rows)
        println("Wrote Shadow app delivery fixture: $outputPath")
    }

    private fun shadowFixtureScenarios(): List<Triple<String, String, () -> QuicScenario<NodeProgramFactory>>> =
        listOf(
            Triple(
                QuicScenarios.SLOW_START,
                "${QuicScenarios.SLOW_START}-shadow-packet-receipts.csv",
                { QuicScenarios.slowStart() }
            ),
            Triple(
                QuicScenarios.SINGLE_TRANSFER_8MB,
                "${QuicScenarios.SINGLE_TRANSFER_8MB}-shadow-packet-receipts.csv",
                { QuicScenarios.singleTransfer8Mb() }
            ),
            Triple(
                QuicScenarios.SINGLE_TRANSFER_8MB_HALF_RECEIVER_BW,
                "${QuicScenarios.SINGLE_TRANSFER_8MB_HALF_RECEIVER_BW}-shadow-packet-receipts.csv",
                { QuicScenarios.singleTransfer8MbHalfReceiverBandwidth() }
            ),
            Triple(
                QuicScenarios.INBOUND_CONGESTION,
                "${QuicScenarios.INBOUND_CONGESTION}-shadow-packet-receipts.csv",
                { QuicScenarios.inboundCongestion() }
            ),
            Triple(
                QuicScenarios.OUTBOUND_CONGESTION,
                "${QuicScenarios.OUTBOUND_CONGESTION}-shadow-packet-receipts.csv",
                { QuicScenarios.outboundCongestion() }
            )
        )

    private fun compareAppDeliveryCurve(
        expected: List<AppDeliveryEvent>,
        actual: List<AppDeliveryEvent>,
        transferStart: Duration,
        maxMeanAbsoluteDifferenceRatio: Double
    ): AppDeliveryComparison {
        val expectedEvents = expected
            .filter { it.receivedAt >= transferStart }
            .sortedBy { it.receivedAt }
        val actualEvents = actual
            .filter { it.receivedAt >= transferStart }
            .sortedBy { it.receivedAt }
        val expectedBytes = expectedEvents.sumOf { it.payloadBytes.toLong() }
        val actualBytes = actualEvents.sumOf { it.payloadBytes.toLong() }
        val expectedCompleteAt = expectedEvents.lastOrNull()?.receivedAt ?: transferStart
        val actualCompleteAt = actualEvents.lastOrNull()?.receivedAt ?: transferStart
        val expectedTransferDuration = expectedCompleteAt - transferStart
        val times = (
            listOf(transferStart) +
                expectedEvents.map { it.receivedAt } +
                actualEvents.map { it.receivedAt }
            )
            .distinct()
            .sorted()

        var expectedDeliveredBytes = 0L
        var actualDeliveredBytes = 0L
        var expectedIndex = 0
        var actualIndex = 0
        var previousTime = times.firstOrNull() ?: transferStart
        var absoluteByteNanos = 0.0

        times.forEach { time ->
            absoluteByteNanos += abs(expectedDeliveredBytes - actualDeliveredBytes).toDouble() *
                (time - previousTime).inWholeNanoseconds
            while (expectedIndex < expectedEvents.size && expectedEvents[expectedIndex].receivedAt == time) {
                expectedDeliveredBytes += expectedEvents[expectedIndex].payloadBytes
                expectedIndex++
            }
            while (actualIndex < actualEvents.size && actualEvents[actualIndex].receivedAt == time) {
                actualDeliveredBytes += actualEvents[actualIndex].payloadBytes
                actualIndex++
            }
            previousTime = time
        }

        val meanAbsoluteDifference = (absoluteByteNanos / expectedBytes.coerceAtLeast(1L)).toLong().nanoseconds
        val maxAllowedDifference = (
            expectedTransferDuration.inWholeNanoseconds * maxMeanAbsoluteDifferenceRatio
            ).toLong().nanoseconds
        return AppDeliveryComparison(
            expectedRows = expectedEvents.size,
            actualRows = actualEvents.size,
            expectedBytes = expectedBytes,
            actualBytes = actualBytes,
            expectedTransferDuration = expectedTransferDuration,
            meanAbsoluteDifference = meanAbsoluteDifference,
            maxAllowedDifference = maxAllowedDifference,
            expectedCompleteAt = expectedCompleteAt,
            actualCompleteAt = actualCompleteAt
        )
    }

    private data class AppDeliveryEvent(
        val receivedAt: Duration,
        val payloadBytes: Int
    )

    private data class AppDeliveryComparison(
        val expectedRows: Int,
        val actualRows: Int,
        val expectedBytes: Long,
        val actualBytes: Long,
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

    private data class ComparisonStats(
        val scenarioName: String,
        val comparison: AppDeliveryComparison
    ) {
        fun toReportLine(): String =
            "Sim-vs-Shadow app delivery [$scenarioName]: " +
                "meanAbs=${comparison.meanAbsoluteDifference.toMillisString()} " +
                "max=${comparison.maxAllowedDifference.toMillisString()} " +
                "normalized=${comparison.normalizedDifferencePercent.formatPercent()}% " +
                "maxNormalized=${comparison.maxAllowedDifferencePercent.formatPercent()}% " +
                "completionDelta=${comparison.completionDelta.toMillisString()} " +
                "shadowDuration=${comparison.expectedTransferDuration.toMillisString()} " +
                "rows=${comparison.actualRows}/${comparison.expectedRows} " +
                "bytes=${comparison.actualBytes}/${comparison.expectedBytes}"

        fun toFailureMessage(): String =
            "$scenarioName app data delivery curve mean absolute difference " +
                "${comparison.meanAbsoluteDifference.toMillisString()} " +
                "(${comparison.normalizedDifferencePercent.formatPercent()}% of Shadow transfer time) " +
                "exceeds ${comparison.maxAllowedDifference.toMillisString()}; " +
                "maxNormalized=${comparison.maxAllowedDifferencePercent.formatPercent()}% " +
                "completionDelta=${comparison.completionDelta.toMillisString()} " +
                "expectedRows=${comparison.expectedRows} " +
                "actualRows=${comparison.actualRows} " +
                "expectedBytes=${comparison.expectedBytes} " +
                "actualBytes=${comparison.actualBytes} " +
                "shadowTransferDuration=${comparison.expectedTransferDuration} " +
                "shadowCompleteAt=${comparison.expectedCompleteAt} " +
                "actualCompleteAt=${comparison.actualCompleteAt}"

        private fun Duration.toMillisString(): String =
            "%.3fms".format(inWholeNanoseconds / 1_000_000.0)

        private fun Double.formatPercent(): String =
            "%.3f".format(this)
    }

    companion object {
        private val comparisonStats = mutableListOf<ComparisonStats>()

        private fun recordStats(stats: ComparisonStats) {
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
            println("Sim-vs-Shadow app delivery summary:")
            comparisonStats
                .sortedBy { it.scenarioName }
                .forEach { println(it.toReportLine()) }
        }
    }
}
