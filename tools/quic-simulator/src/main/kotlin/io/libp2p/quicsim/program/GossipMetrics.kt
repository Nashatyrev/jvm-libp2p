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

    data class MessageReceiptKey(
        val publishingNodeId: SimNodeId,
        val receivingNodeId: SimNodeId
    )

    data class DisseminationComparison(
        val leftReceiptCount: Int,
        val rightReceiptCount: Int,
        val matchedPairCount: Int,
        val leftOnlyPairCount: Int,
        val rightOnlyPairCount: Int,
        val pairMeanSignedDeltaMs: Double?,
        val pairMeanAbsoluteDeltaMs: Double?,
        val pairRootMeanSquareDeltaMs: Double?,
        val pairP50AbsoluteDeltaMs: Double?,
        val pairP95AbsoluteDeltaMs: Double?,
        val pairMaxAbsoluteDeltaMs: Double?,
        val pairDifferenceScoreMs: Double,
        val cdfComparedCount: Int,
        val cdfMeanSignedDeltaMs: Double?,
        val cdfMeanAbsoluteDeltaMs: Double?,
        val cdfP50AbsoluteDeltaMs: Double?,
        val cdfP95AbsoluteDeltaMs: Double?,
        val cdfMaxAbsoluteDeltaMs: Double?,
        val cdfDifferenceScoreMs: Double,
        val missingPenaltyMs: Double
    ) {
        val differenceScoreMs: Double
            get() = cdfDifferenceScoreMs
    }

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

    fun compareDissemination(
        left: List<MessageReceipt>,
        right: List<MessageReceipt>,
        missingPenaltyMs: Double = defaultMissingPenaltyMs(left, right)
    ): DisseminationComparison {
        val leftByKey = firstReceiptsByKey(left)
        val rightByKey = firstReceiptsByKey(right)
        val leftKeys = leftByKey.keys
        val rightKeys = rightByKey.keys
        val matchedKeys = leftKeys intersect rightKeys
        val unionKeyCount = (leftKeys + rightKeys).size

        val pairDeltas = matchedKeys.map { key ->
            millis(rightByKey.getValue(key).receivedAt - leftByKey.getValue(key).receivedAt)
        }
        val pairAbsDeltas = pairDeltas.map(::abs)
        val unmatchedPairCount = unionKeyCount - matchedKeys.size
        val pairMissingFraction = if (unionKeyCount == 0) 0.0 else unmatchedPairCount.toDouble() / unionKeyCount

        val leftTimes = left.map { millis(it.receivedAt) }.sorted()
        val rightTimes = right.map { millis(it.receivedAt) }.sorted()
        val cdfComparedCount = minOf(leftTimes.size, rightTimes.size)
        val cdfDeltas = (0 until cdfComparedCount).map { rightTimes[it] - leftTimes[it] }
        val cdfAbsDeltas = cdfDeltas.map(::abs)
        val cdfMissingCount = abs(leftTimes.size - rightTimes.size)
        val cdfUnionCount = maxOf(leftTimes.size, rightTimes.size)
        val cdfMissingFraction = if (cdfUnionCount == 0) 0.0 else cdfMissingCount.toDouble() / cdfUnionCount

        return DisseminationComparison(
            leftReceiptCount = left.size,
            rightReceiptCount = right.size,
            matchedPairCount = matchedKeys.size,
            leftOnlyPairCount = leftKeys.size - matchedKeys.size,
            rightOnlyPairCount = rightKeys.size - matchedKeys.size,
            pairMeanSignedDeltaMs = pairDeltas.meanOrNull(),
            pairMeanAbsoluteDeltaMs = pairAbsDeltas.meanOrNull(),
            pairRootMeanSquareDeltaMs = pairDeltas.rootMeanSquareOrNull(),
            pairP50AbsoluteDeltaMs = pairAbsDeltas.percentileOrNull(0.50),
            pairP95AbsoluteDeltaMs = pairAbsDeltas.percentileOrNull(0.95),
            pairMaxAbsoluteDeltaMs = pairAbsDeltas.maxOrNull(),
            pairDifferenceScoreMs = (pairAbsDeltas.meanOrNull() ?: 0.0) + pairMissingFraction * missingPenaltyMs,
            cdfComparedCount = cdfComparedCount,
            cdfMeanSignedDeltaMs = cdfDeltas.meanOrNull(),
            cdfMeanAbsoluteDeltaMs = cdfAbsDeltas.meanOrNull(),
            cdfP50AbsoluteDeltaMs = cdfAbsDeltas.percentileOrNull(0.50),
            cdfP95AbsoluteDeltaMs = cdfAbsDeltas.percentileOrNull(0.95),
            cdfMaxAbsoluteDeltaMs = cdfAbsDeltas.maxOrNull(),
            cdfDifferenceScoreMs = (cdfAbsDeltas.meanOrNull() ?: 0.0) + cdfMissingFraction * missingPenaltyMs,
            missingPenaltyMs = missingPenaltyMs
        )
    }

    fun firstReceiptsByKey(receipts: List<MessageReceipt>): Map<MessageReceiptKey, MessageReceipt> =
        receipts
            .groupBy { MessageReceiptKey(it.publishingNodeId, it.receivingNodeId) }
            .mapValues { (_, values) -> values.minByOrNull { it.receivedAt }!! }

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
