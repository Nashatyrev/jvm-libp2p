package io.libp2p.quicsim.program

import io.libp2p.quicsim.scenario.QuicScenarioEvent
import io.libp2p.quicsim.sim.SimNodeId
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

object GossipMetrics {
    data class MessageReceipt(
        val receivedAt: Duration,
        val receivingNodeId: SimNodeId,
        val publishingNodeId: SimNodeId
    )

    data class MessagePublication(
        val publishedAt: Duration,
        val publishingNodeId: SimNodeId
    )

    data class DisseminationComparison(
        val leftReceiptCount: Int,
        val rightReceiptCount: Int,
        val comparedReceiptCount: Int,
        val missingReceiptCount: Int,
        val meanSignedDeltaMs: Double?,
        val meanAbsoluteDeltaMs: Double?,
        val rootMeanSquareDeltaMs: Double?,
        val p50AbsoluteDeltaMs: Double?,
        val p95AbsoluteDeltaMs: Double?,
        val maxAbsoluteDeltaMs: Double?,
        val integralDifferenceMs: Double?,
        val differenceScoreMs: Double,
        val missingPenaltyMs: Double
    )

    fun messageReceipts(events: List<QuicScenarioEvent>): List<MessageReceipt> =
        events.filterIsInstance<QuicScenarioEvent.GossipMessageReceived>()
            .map {
                MessageReceipt(
                    receivedAt = it.at,
                    receivingNodeId = it.nodeId,
                    publishingNodeId = it.publisherNodeId
                )
            }
            .sortedWith(compareBy({ it.receivedAt }, { it.receivingNodeId }, { it.publishingNodeId }))

    fun messagePublications(events: List<QuicScenarioEvent>): List<MessagePublication> =
        events.filterIsInstance<QuicScenarioEvent.GossipMessagePublished>()
            .map {
                MessagePublication(
                    publishedAt = it.at,
                    publishingNodeId = it.nodeId
                )
            }
            .sortedWith(compareBy({ it.publishedAt }, { it.publishingNodeId }))

    fun compareDissemination(
        left: List<MessageReceipt>,
        right: List<MessageReceipt>,
        missingPenaltyMs: Double = defaultMissingPenaltyMs(left, right)
    ): DisseminationComparison {
        val leftTimes = left.map { millis(it.receivedAt) }.sorted()
        val rightTimes = right.map { millis(it.receivedAt) }.sorted()
        val comparedCount = minOf(leftTimes.size, rightTimes.size)
        val deltas = (0 until comparedCount).map { rightTimes[it] - leftTimes[it] }
        val absoluteDeltas = deltas.map(::abs)
        val missingCount = abs(leftTimes.size - rightTimes.size)
        val unionCount = maxOf(leftTimes.size, rightTimes.size)
        val missingFraction = if (unionCount == 0) 0.0 else missingCount.toDouble() / unionCount
        val integralDifferenceMs = absoluteDeltas.meanOrNull()

        return DisseminationComparison(
            leftReceiptCount = left.size,
            rightReceiptCount = right.size,
            comparedReceiptCount = comparedCount,
            missingReceiptCount = missingCount,
            meanSignedDeltaMs = deltas.meanOrNull(),
            meanAbsoluteDeltaMs = absoluteDeltas.meanOrNull(),
            rootMeanSquareDeltaMs = deltas.rootMeanSquareOrNull(),
            p50AbsoluteDeltaMs = absoluteDeltas.percentileOrNull(0.50),
            p95AbsoluteDeltaMs = absoluteDeltas.percentileOrNull(0.95),
            maxAbsoluteDeltaMs = absoluteDeltas.maxOrNull(),
            integralDifferenceMs = integralDifferenceMs,
            differenceScoreMs = (integralDifferenceMs ?: 0.0) + missingFraction * missingPenaltyMs,
            missingPenaltyMs = missingPenaltyMs
        )
    }

    private fun defaultMissingPenaltyMs(left: List<MessageReceipt>, right: List<MessageReceipt>): Double {
        val maxTimeMs = (left + right).maxOfOrNull { millis(it.receivedAt) } ?: 0.0
        return maxOf(1_000.0, maxTimeMs)
    }

    private fun millis(duration: Duration): Double =
        duration.inWholeNanoseconds / 1_000_000.0

    fun durationFromNanoseconds(nanoseconds: Long): Duration =
        nanoseconds.nanoseconds

    private fun List<Double>.meanOrNull(): Double? =
        if (isEmpty()) null else sum() / size

    private fun List<Double>.rootMeanSquareOrNull(): Double? =
        if (isEmpty()) null else sqrt(sumOf { it * it } / size)

    private fun List<Double>.percentileOrNull(percentile: Double): Double? {
        if (isEmpty()) return null
        require(percentile in 0.0..1.0) { "percentile must be in [0, 1]" }
        val sorted = sorted()
        val index = ((sorted.size - 1) * percentile).toInt()
        return sorted[index]
    }
}
