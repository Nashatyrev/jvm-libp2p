package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.DataChunkMetrics
import io.libp2p.quicsim.scenario.QuicScenarios
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class InboundCongestionAppDeliveryGoldenTest {
    @Test
    fun `simulated inbound congestion matches stored shadow app delivery curve`() {
        val expected = readPacketReceiptCsv(
            resourceLines("/io/libp2p/quicsim/runner/inbound-congestion-shadow-packet-receipts.csv")
        )
        val actual = DataChunkMetrics.packetReceipts(
            SimulatedQuicScenarioRunner(
                latencyWindowParallelism = 20,
                bandwidthQueueDiscipline = BandwidthQueueDiscipline.SHADOW_LIKE
            ).run(QuicScenarios.inboundCongestion()).events
        ).map {
            AppDeliveryEvent(
                receivedAt = it.receivedAt,
                payloadBytes = it.payloadBytes
            )
        }
        val comparison = compareAppDeliveryCurve(
            expected = expected,
            actual = actual,
            transferStart = INBOUND_CONGESTION_TRANSFER_START
        )

        assertEquals(
            comparison.expectedBytes,
            comparison.actualBytes,
            "Expected sim to deliver the same amount of app data as the stored Shadow run"
        )
        assertTrue(
            comparison.meanAbsoluteDifference <= comparison.maxAllowedDifference,
            "App data delivery curve mean absolute difference " +
                "${comparison.meanAbsoluteDifference} " +
                "(${comparison.normalizedDifferencePercent.formatPercent()}% of Shadow transfer time) " +
                "exceeds ${comparison.maxAllowedDifference} " +
                "(${(MAX_MEAN_ABSOLUTE_DIFFERENCE_OF_TRANSFER * 100.0).formatPercent()}%); " +
                "expectedRows=${comparison.expectedRows} " +
                "actualRows=${comparison.actualRows} " +
                "expectedBytes=${comparison.expectedBytes} " +
                "actualBytes=${comparison.actualBytes} " +
                "shadowTransferDuration=${comparison.expectedTransferDuration} " +
                "shadowCompleteAt=${comparison.expectedCompleteAt} " +
                "actualCompleteAt=${comparison.actualCompleteAt}"
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

    private fun compareAppDeliveryCurve(
        expected: List<AppDeliveryEvent>,
        actual: List<AppDeliveryEvent>,
        transferStart: Duration
    ): AppDeliveryComparison {
        val expectedEvents = expected.sortedBy { it.receivedAt }
        val actualEvents = actual.sortedBy { it.receivedAt }
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
            expectedTransferDuration.inWholeNanoseconds * MAX_MEAN_ABSOLUTE_DIFFERENCE_OF_TRANSFER
            ).toLong().nanoseconds
        return AppDeliveryComparison(
            expectedRows = expected.size,
            actualRows = actual.size,
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
        val normalizedDifferencePercent: Double
            get() = meanAbsoluteDifference.inWholeNanoseconds.toDouble() /
                expectedTransferDuration.inWholeNanoseconds.coerceAtLeast(1L) *
                100.0
    }

    private fun Double.formatPercent(): String =
        "%.3f".format(this)

    private companion object {
        val INBOUND_CONGESTION_TRANSFER_START = 10.seconds
        const val MAX_MEAN_ABSOLUTE_DIFFERENCE_OF_TRANSFER = 0.01
    }
}
