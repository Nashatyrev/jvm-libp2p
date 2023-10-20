package io.libp2p.pubsub.erasure.router.strategy

import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.isComplete

interface AckSendStrategy {

    fun onInboundSample(msg: ErasureSample): Boolean

    fun onNewSamples(newSamples: Set<ErasureSample>): Boolean

    companion object {

        val ALWAYS_ON_NEW_SAMPLES = object : AckSendStrategy {
            override fun onInboundSample(msg: ErasureSample) = false
            override fun onNewSamples(newSamples: Set<ErasureSample>) = true
        }

        val ALWAYS_RESPOND_TO_INBOUND_SAMPLES = object : AckSendStrategy {
            override fun onInboundSample(msg: ErasureSample) = true
            override fun onNewSamples(newSamples: Set<ErasureSample>) = false
        }

        fun whenMessageCompleteOnly(msg: SampledMessage) = object : AckSendStrategy {
            override fun onInboundSample(msg: ErasureSample) = true
            override fun onNewSamples(newSamples: Set<ErasureSample>) = msg.isComplete()
        }
    }
}