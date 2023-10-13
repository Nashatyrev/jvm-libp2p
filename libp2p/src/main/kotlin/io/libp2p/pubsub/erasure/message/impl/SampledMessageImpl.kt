package io.libp2p.pubsub.erasure.message.impl

import io.libp2p.pubsub.erasure.ErasureCoder
import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.MutableSampleBox
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampleBoxObserver
import io.libp2p.pubsub.erasure.message.SamplesBoxImpl

class SampledMessageImpl(
    override val header: ErasureHeader,
    private val erasureCoder: ErasureCoder
) : MutableSampledMessage {

    private val observer = SampleBoxObserver { _, _ -> sampleAdded() }
    override val sampleBox: MutableSampleBox = SamplesBoxImpl()
        .also {
            it.observers += observer
        }

    fun sampleAdded() {
        if (sampleBox.samples.size >= header.recoverSampleCount) {
            sampleBox.observers -= observer
            erasureCoder.restore(this)
        }
    }
}