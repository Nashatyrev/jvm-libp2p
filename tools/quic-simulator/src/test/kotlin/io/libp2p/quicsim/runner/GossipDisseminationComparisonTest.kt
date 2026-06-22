package io.libp2p.quicsim.runner

import io.libp2p.quicsim.program.GossipMetrics
import io.libp2p.quicsim.program.GossipMetrics.MessageReceipt
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
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

        writeSummaryCsv(
            comparison = comparison,
            leftPath = leftPath,
            rightPath = rightPath,
            outputPath = outputDir.resolve("gossip-dissemination-comparison-summary.csv")
        )
        writePairDeltaCsv(
            left = left,
            right = right,
            outputPath = outputDir.resolve("gossip-dissemination-pair-deltas.csv")
        )
        writeCdfDeltaCsv(
            left = left,
            right = right,
            outputPath = outputDir.resolve("gossip-dissemination-cdf-deltas.csv")
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
                appendMetric("cdf_difference_score_ms", comparison.cdfDifferenceScoreMs)
                appendMetric("pair_difference_score_ms", comparison.pairDifferenceScoreMs)
                appendMetric("left_receipt_count", comparison.leftReceiptCount)
                appendMetric("right_receipt_count", comparison.rightReceiptCount)
                appendMetric("matched_pair_count", comparison.matchedPairCount)
                appendMetric("left_only_pair_count", comparison.leftOnlyPairCount)
                appendMetric("right_only_pair_count", comparison.rightOnlyPairCount)
                appendMetric("pair_mean_signed_delta_ms", comparison.pairMeanSignedDeltaMs)
                appendMetric("pair_mean_absolute_delta_ms", comparison.pairMeanAbsoluteDeltaMs)
                appendMetric("pair_root_mean_square_delta_ms", comparison.pairRootMeanSquareDeltaMs)
                appendMetric("pair_p50_absolute_delta_ms", comparison.pairP50AbsoluteDeltaMs)
                appendMetric("pair_p95_absolute_delta_ms", comparison.pairP95AbsoluteDeltaMs)
                appendMetric("pair_max_absolute_delta_ms", comparison.pairMaxAbsoluteDeltaMs)
                appendMetric("cdf_compared_count", comparison.cdfComparedCount)
                appendMetric("cdf_mean_signed_delta_ms", comparison.cdfMeanSignedDeltaMs)
                appendMetric("cdf_mean_absolute_delta_ms", comparison.cdfMeanAbsoluteDeltaMs)
                appendMetric("cdf_p50_absolute_delta_ms", comparison.cdfP50AbsoluteDeltaMs)
                appendMetric("cdf_p95_absolute_delta_ms", comparison.cdfP95AbsoluteDeltaMs)
                appendMetric("cdf_max_absolute_delta_ms", comparison.cdfMaxAbsoluteDeltaMs)
                appendMetric("missing_penalty_ms", comparison.missingPenaltyMs)
            }
        )
        println("Wrote gossip dissemination comparison summary: $outputPath")
    }

    private fun writePairDeltaCsv(
        left: List<MessageReceipt>,
        right: List<MessageReceipt>,
        outputPath: Path
    ) {
        val leftByKey = GossipMetrics.firstReceiptsByKey(left)
        val rightByKey = GossipMetrics.firstReceiptsByKey(right)
        val keys = (leftByKey.keys + rightByKey.keys)
            .sortedWith(compareBy({ it.publishingNodeId }, { it.receivingNodeId }))

        outputPath.writeText(
            buildString {
                appendLine("publishing_node_id,receiving_node_id,left_time_s,right_time_s,right_minus_left_ms,abs_delta_ms,status")
                keys.forEach { key ->
                    val leftReceipt = leftByKey[key]
                    val rightReceipt = rightByKey[key]
                    val delta = if (leftReceipt != null && rightReceipt != null) {
                        millis(rightReceipt.receivedAt - leftReceipt.receivedAt)
                    } else {
                        null
                    }
                    appendLine(
                        listOf(
                            key.publishingNodeId,
                            key.receivingNodeId,
                            leftReceipt?.receivedAt?.let(::seconds).orEmpty(),
                            rightReceipt?.receivedAt?.let(::seconds).orEmpty(),
                            delta?.let(::decimal).orEmpty(),
                            delta?.let { decimal(abs(it)) }.orEmpty(),
                            when {
                                leftReceipt != null && rightReceipt != null -> "matched"
                                leftReceipt != null -> "left_only"
                                else -> "right_only"
                            }
                        ).joinToString(",")
                    )
                }
            }
        )
        println("Wrote gossip dissemination pair deltas: $outputPath")
    }

    private fun writeCdfDeltaCsv(
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
        println("Wrote gossip dissemination CDF deltas: $outputPath")
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

    private fun Any?.orEmpty(): Any = this ?: ""
}
