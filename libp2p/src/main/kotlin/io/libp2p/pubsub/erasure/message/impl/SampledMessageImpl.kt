package io.libp2p.pubsub.erasure.message.impl

import io.libp2p.etc.types.Union
import io.libp2p.pubsub.erasure.ErasureCoder
import io.libp2p.pubsub.erasure.SampleIndex
import io.libp2p.pubsub.erasure.SampledMessageCollector
import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.BitSet

class SampledMessageImpl(
    override val header: ErasureHeader,
    private val erasureCoder: ErasureCoder
) : SampledMessage, SampledMessageCollector {

    private val samples =
        Union<MutableMap<Int, ErasureSample>, SampledMessage>()
            .set(mutableMapOf())

    override val recoveredMessage: CompletableDeferred<SourceMessage> = CompletableDeferred()
    override val availableSamples: MutableStateFlow<BitSet> = MutableStateFlow(BitSet(header.totalSampleCount))

    override fun addSample(sample: ErasureSample) {
        samples.apply1 { partialSamples ->
            partialSamples[sample.sampleIndex] = sample
            val newBits = availableSamples.value.clone() as BitSet
            newBits.set(sample.sampleIndex)
            availableSamples.value = newBits

            if (partialSamples.size >= header.recoverSampleCount) {
                val sourceMessage = erasureCoder.restore(this)
                recoveredMessage.complete(sourceMessage)
                val fullSampledMessage = erasureCoder.extend(sourceMessage, header.totalSampleCount)
                samples.set(fullSampledMessage)
                availableSamples.value = fullSampledMessage.availableSamples.value
            }
        }
    }

    override fun getSample(idx: SampleIndex): ErasureSample =
        samples.map({ it[idx] }, { it.getSample(idx) })
            ?: throw IndexOutOfBoundsException("No sample with index $idx")
}