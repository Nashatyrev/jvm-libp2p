package io.libp2p.pubsub.erasure

import io.libp2p.etc.types.WBytes
import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage
import io.libp2p.pubsub.erasure.message.impl.ErasureHeaderImpl
import io.libp2p.pubsub.erasure.message.impl.SampledMessageImpl

fun WBytes.slice(start: Int, len: Int) = WBytes(this.array.sliceArray(start until start + len))

fun WBytes.chunked(maxSize: Int) =
    this.array.indices.chunked(maxSize).map { this.slice(it.first(), it.size) }

class TestErasureCoder(
    val sampleSize: Int,
    val extensionFactor: Int
) : ErasureCoder {

    private fun generateTestSamples(count: Int, messageId: MessageId): List<ErasureSample> =
        (0..count)
            .map { sampleIndex ->
                val sampleData = ByteArray(sampleSize) { sampleIndex.toByte() }.toWBytes()
                ErasureSample(messageId, sampleIndex, sampleData)
            }

    override fun extend(msg: SourceMessage): SampledMessage {
        val originalSamplesCount = (msg.blob.array.size - 1) / sampleSize + 1
        val extendedSamplesCount = originalSamplesCount * extensionFactor

        val header = ErasureHeaderImpl(msg.topic, msg.messageId, extendedSamplesCount, originalSamplesCount)
        val sampledMessage = SampledMessageImpl(header, this)
        val samples = generateTestSamples(extendedSamplesCount, msg.messageId)
        sampledMessage.sampleBox.addSamples(samples)
        return sampledMessage
    }

    override fun restore(sampledMessage: MutableSampledMessage): SourceMessage {
        require(sampledMessage.sampleBox.samples.size >= sampledMessage.header.recoverSampleCount)
        val allSamples =
            generateTestSamples(sampledMessage.header.totalSampleCount, sampledMessage.header.messageId).toSet()
        val missingSamples = allSamples - sampledMessage.sampleBox.samples
        sampledMessage.sampleBox.addSamples(missingSamples)
        val origMessageSize = sampledMessage.header.recoverSampleCount * sampleSize // TODO not exact size
        return SourceMessage(
            sampledMessage.header.topic,
            sampledMessage.header.messageId,
            ByteArray(origMessageSize).toWBytes()
        )
    }
}