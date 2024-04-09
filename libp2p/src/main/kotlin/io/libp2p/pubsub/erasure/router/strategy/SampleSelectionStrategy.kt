package io.libp2p.pubsub.erasure.router.strategy

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.SampleIndex
import io.libp2p.pubsub.erasure.message.ErasureSample
import kotlin.random.Random

interface SampleSelectionStrategy {

    fun selectSampleFromCandidates(toPeer: PeerId, candidates: List<ErasureSample>): ErasureSample

    override fun toString(): String

    companion object {
        fun createRandomStrategy(rnd: Random): SampleSelectionStrategy = object : SampleSelectionStrategy {
            override fun selectSampleFromCandidates(toPeer: PeerId, candidates: List<ErasureSample>): ErasureSample =
                candidates[rnd.nextInt(candidates.size)]

            override fun toString(): String = "rnd"
        }

        fun createBalancedStrategy(rnd: Random) = object : SampleSelectionStrategy {
            private val sampleCount = mutableMapOf<SampleIndex, Int>()

            private fun getSampleCount(idx: SampleIndex) = sampleCount[idx] ?: 0
            private fun incSampleCount(idx: SampleIndex) = sampleCount.compute(idx) { _, oldCount ->
                (oldCount ?: 0) + 1
            }

            override fun selectSampleFromCandidates(toPeer: PeerId, candidates: List<ErasureSample>): ErasureSample {
                require(candidates.isNotEmpty())
                val candidatesWithCount = candidates.map { it to getSampleCount(it.sampleIndex) }
                val minCount = candidatesWithCount.minOf { it.second }
                val minCandidates = candidatesWithCount
                    .filter { it.second == minCount }
                    .map { it.first }
                val sample = minCandidates[rnd.nextInt(minCandidates.size)]
//                val sample = candidates.minOfWith(
//                    compareBy( { getSampleCount(it.sampleIndex) }, {it.sampleIndex} ),
//                    { it }
//                )
                incSampleCount(sample.sampleIndex)
                return sample
            }

            override fun toString(): String  = "bal"
        }
    }
}