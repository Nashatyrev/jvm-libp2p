package io.libp2p.pubsub.erasure.router.strategy

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.SampleIndex
import io.libp2p.pubsub.erasure.message.ErasureSample
import kotlin.random.Random

fun interface SampleSelectionStrategy {

    fun selectSampleFromCandidates(toPeer: PeerId, candidates: List<ErasureSample>): ErasureSample

    companion object {
        fun createRandomStrategy(rnd: Random): SampleSelectionStrategy = SampleSelectionStrategy { _, candidates ->
            candidates[rnd.nextInt(candidates.size)]
        }

        fun createBalancedStrategy() = object : SampleSelectionStrategy {
            private val sampleCount = mutableMapOf<SampleIndex, Int>()

            private fun getSampleCount(idx: SampleIndex) = sampleCount[idx] ?: 0
            private fun incSampleCount(idx: SampleIndex) = sampleCount.compute(idx) { _, oldCount ->
                (oldCount ?: 0) + 1
            }

            override fun selectSampleFromCandidates(toPeer: PeerId, candidates: List<ErasureSample>): ErasureSample {
                require(candidates.isNotEmpty())
                val sample = candidates.minOfWith(
                    compareBy( { getSampleCount(it.sampleIndex) }, {it.sampleIndex} ),
                    { it }
                )
                incSampleCount(sample.sampleIndex)
                return sample
            }
        }
    }
}