package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.GossipMetrics
import io.libp2p.quicsim.program.GossipMetrics.MessageReceipt
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.time.Duration

class GossipDisseminationComparisonTest {
    @Test
    fun `compare sample gossip message receipt csvs`() {
        val leftPath = comparisonPath(
            property = "gossipCompare.left",
            defaultPath = sampleGossipDir().resolve("sample-gossip-simulated-message-receipts.csv")
        )
        val rightPath = comparisonPath(
            property = "gossipCompare.right",
            defaultPath = sampleGossipDir().resolve("sample-gossip-shadow-message-receipts.csv")
        )
        assumeTrue(leftPath.exists(), "Missing left receipt CSV: $leftPath")
        assumeTrue(rightPath.exists(), "Missing right receipt CSV: $rightPath")

        val left = readMessageReceiptCsv(leftPath)
        val right = readMessageReceiptCsv(rightPath)
        val comparison = GossipMetrics.compareDissemination(left, right)
        val outputDir = outputDir().also { it.createDirectories() }
        outputDir.resolve("gossip-dissemination-pair-deltas.csv").deleteIfExists()
        outputDir.resolve("gossip-dissemination-cdf-deltas.csv").deleteIfExists()

        writeSummaryCsv(
            comparison = comparison,
            leftPath = leftPath,
            rightPath = rightPath,
            outputPath = outputDir.resolve("gossip-dissemination-comparison-summary.csv")
        )
        writeIntegralDeltaCsv(
            left = left,
            right = right,
            outputPath = outputDir.resolve("gossip-dissemination-integral-deltas.csv")
        )
    }

    private fun readMessageReceiptCsv(path: Path): List<MessageReceipt> {
        val lines = path.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val columns = lines.first().split(',').mapIndexed { index, name -> name to index }.toMap()
        fun column(name: String): Int =
            columns[name] ?: throw IllegalArgumentException("CSV $path does not contain column $name")

        val timeNs = column("time_ns")
        val receivingNodeId = column("receiving_node_id")
        val publishingNodeId = column("publishing_node_id")

        return lines.drop(1)
            .map { line ->
                val parts = line.split(',')
                MessageReceipt(
                    receivedAt = GossipMetrics.durationFromNanoseconds(parts[timeNs].toLong()),
                    receivingNodeId = parts[receivingNodeId].toInt(),
                    publishingNodeId = parts[publishingNodeId].toInt()
                )
            }
            .sortedWith(compareBy({ it.receivedAt }, { it.receivingNodeId }, { it.publishingNodeId }))
    }

    private fun writeSummaryCsv(
        comparison: GossipMetrics.DisseminationComparison,
        leftPath: Path,
        rightPath: Path,
        outputPath: Path
    ) {
        outputPath.writeText(
            buildString {
                appendLine("metric,value")
                appendMetric("left_file", leftPath)
                appendMetric("right_file", rightPath)
                appendMetric("difference_score_ms", comparison.differenceScoreMs)
                appendMetric("integral_difference_ms", comparison.integralDifferenceMs)
                appendMetric("left_receipt_count", comparison.leftReceiptCount)
                appendMetric("right_receipt_count", comparison.rightReceiptCount)
                appendMetric("compared_receipt_count", comparison.comparedReceiptCount)
                appendMetric("missing_receipt_count", comparison.missingReceiptCount)
                appendMetric("mean_signed_delta_ms", comparison.meanSignedDeltaMs)
                appendMetric("mean_absolute_delta_ms", comparison.meanAbsoluteDeltaMs)
                appendMetric("root_mean_square_delta_ms", comparison.rootMeanSquareDeltaMs)
                appendMetric("p50_absolute_delta_ms", comparison.p50AbsoluteDeltaMs)
                appendMetric("p95_absolute_delta_ms", comparison.p95AbsoluteDeltaMs)
                appendMetric("max_absolute_delta_ms", comparison.maxAbsoluteDeltaMs)
                appendMetric("missing_penalty_ms", comparison.missingPenaltyMs)
            }
        )
        println("Wrote gossip dissemination comparison summary: $outputPath")
    }

    private fun writeIntegralDeltaCsv(
        left: List<MessageReceipt>,
        right: List<MessageReceipt>,
        outputPath: Path
    ) {
        val leftTimes = left.map { it.receivedAt }.sorted()
        val rightTimes = right.map { it.receivedAt }.sorted()
        val comparedCount = minOf(leftTimes.size, rightTimes.size)

        outputPath.writeText(
            buildString {
                appendLine("rank,left_time_s,right_time_s,right_minus_left_ms,abs_delta_ms")
                (0 until comparedCount).forEach { index ->
                    val delta = millis(rightTimes[index] - leftTimes[index])
                    appendLine(
                        listOf(
                            index,
                            seconds(leftTimes[index]),
                            seconds(rightTimes[index]),
                            decimal(delta),
                            decimal(abs(delta))
                        ).joinToString(",")
                    )
                }
            }
        )
        println("Wrote gossip dissemination integral deltas: $outputPath")
    }

    private fun StringBuilder.appendMetric(name: String, value: Any?) {
        append(name)
        append(',')
        appendLine(value ?: "")
    }

    private fun comparisonPath(property: String, defaultPath: Path): Path =
        System.getProperty(property)?.let(::Path) ?: defaultPath

    private fun sampleGossipDir(): Path =
        Path(System.getProperty("sampleGossipReport.dir", "build/reports/sample-gossip"))

    private fun outputDir(): Path =
        Path(System.getProperty("gossipCompare.dir", "build/reports/sample-gossip-comparison"))

    private fun seconds(duration: Duration): String =
        decimal(duration.inWholeNanoseconds / 1_000_000_000.0, digits = 9)

    private fun millis(duration: Duration): Double =
        duration.inWholeNanoseconds / 1_000_000.0

    private fun decimal(value: Double, digits: Int = 6): String =
        "%.${digits}f".format(Locale.US, value)

}
