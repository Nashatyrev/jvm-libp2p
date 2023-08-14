package io.libp2p.pubsub.erasure

import io.libp2p.etc.types.WBytes
import io.libp2p.pubsub.Topic

typealias SampleIndex = Int

interface ErasureCoder {

    val erasureSerializer: ErasureSerializer

    fun extend(msg: Message, extensionFactor: Double): SampledMessage

    fun restore(samples: SampledMessage): Message

}

interface ErasureHeader {
    val topic: Topic
    val messageId: MessageId
    val totalSampleCount: SampleIndex
    val recoverSampleCount: SampleIndex
}

interface ErasureSample {
    val messageId: MessageId
    val sampleIndex: SampleIndex
}

interface ErasureSerializer {
    fun encodeHeader(header: ErasureHeader): WBytes
    fun decodeHeader(bytes: WBytes): ErasureHeader
    fun encodeSample(sample: ErasureSample): WBytes
    fun decodeSample(bytes: WBytes): ErasureSample
}

