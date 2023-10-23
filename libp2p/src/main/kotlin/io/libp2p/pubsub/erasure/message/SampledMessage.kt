package io.libp2p.pubsub.erasure.message

import io.libp2p.pubsub.erasure.ErasureCoder
import io.libp2p.pubsub.erasure.message.impl.SampledMessageImpl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

interface SampledMessage {

    val header: ErasureHeader

    val sampleBox: ObservableSampleBox

    val restoredMessage: CompletionStage<SourceMessage>

    companion object {
        fun fromHeader(header: ErasureHeader, erasureCoder: ErasureCoder): MutableSampledMessage =
            SampledMessageImpl(header, erasureCoder)
    }
}

interface MutableSampledMessage : SampledMessage {

    override val sampleBox: MutableSampleBox

    override val restoredMessage: CompletableFuture<SourceMessage>
}

fun SampledMessage.isComplete() = this.sampleBox.samples.size >= this.header.recoverSampleCount