package io.libp2p.simulate.pubsub.erasure.router

import io.libp2p.etc.types.WBytes
import io.libp2p.etc.types.repeat
import io.libp2p.etc.types.slice
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.erasure.ErasureCoder
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SamplesBoxImpl
import io.libp2p.pubsub.erasure.message.SourceMessage
import io.libp2p.pubsub.erasure.message.impl.ErasureHeaderImpl
import io.libp2p.pubsub.erasure.message.impl.SampledMessageImpl
import io.libp2p.simulate.pubsub.PubsubMessageSizes

class SimErasureCoder(
    val sampleSize: Int,
    val extensionFactor: Int,
    val proofSize: Int,
    val pubsubMessageSizes: PubsubMessageSizes
) : ErasureCoder {

    private fun generateTestSamples(count: Int, messageId: MessageId, sampleData: WBytes): List<ErasureSample> =
        (0 until count)
            .map { sampleIndex ->
                ErasureSample(messageId, sampleIndex, sampleData)
            }

    override fun extend(msg: SourceMessage): SampledMessage {
        TODO()
//        val origMessageSize = pubMessageGenerator.sizeEstimator()
        val originalSamplesCount = (msg.blob.array.size - 1) / sampleSize + 1
        val extendedSamplesCount = originalSamplesCount * extensionFactor

        val adjustedMessageSize = originalSamplesCount * sampleSize

        val header = ErasureHeaderImpl(msg.topic, msg.messageId, extendedSamplesCount, originalSamplesCount)
        val sampleBytes = msg.blob.slice(0, sampleSize)
        val samples = generateTestSamples(extendedSamplesCount, msg.messageId, sampleBytes)
        val sampleBox = SamplesBoxImpl()
        sampleBox.addSamples(samples)
        return SampledMessageImpl(header, this, sampleBox)
    }

    override fun restore(sampledMessage: MutableSampledMessage): SourceMessage {
        TODO()
        require(sampledMessage.sampleBox.samples.size >= sampledMessage.header.recoverSampleCount)
        require(sampledMessage.sampleBox.samples.map { it.data }.distinct().size == 1)
        val sampleBytes = sampledMessage.sampleBox.samples.first().data
        val allSamples =
            generateTestSamples(
                sampledMessage.header.totalSampleCount,
                sampledMessage.header.messageId,
                sampleBytes
            ).toSet()
        val missingSamples = allSamples - sampledMessage.sampleBox.samples
        sampledMessage.sampleBox.addSamples(missingSamples)
        return SourceMessage(
            sampledMessage.header.topic,
            sampledMessage.header.messageId,
            sampleBytes.repeat(sampledMessage.header.recoverSampleCount)
        )
    }
}