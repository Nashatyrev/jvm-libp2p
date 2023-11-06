package io.libp2p.simulate.pubsub.erasure.router

import io.libp2p.etc.types.toWBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.erasure.ErasureCoder
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SamplesBoxImpl
import io.libp2p.pubsub.erasure.message.SourceMessage
import io.libp2p.pubsub.erasure.message.impl.ErasureHeaderImpl
import io.libp2p.pubsub.erasure.message.impl.SampledMessageImpl
import io.libp2p.simulate.pubsub.MessageBodyGenerator
import io.libp2p.simulate.stats.collect.gossip.SimMessageId

class SimErasureCoder(
    val sampleSize: Int,
    val extensionFactor: Int,
    val sampleExtraSize: Int, // proof size + erasure coding extra
    val headerSize: Int,
    messageBodyGenerator: MessageBodyGenerator
) : ErasureCoder {

    val messageSizeRetriever = messageBodyGenerator.messageSizeRetriever
    val messageSimIdRetriever = messageBodyGenerator.messageIdRetriever
    val msgGenerator = messageBodyGenerator.msgGenerator

    private fun generateSamples(count: Int, messageId: MessageId, simMessageId: SimMessageId): List<ErasureSample> =
        (0 until count)
            .map { sampleIndex ->
                val samplePayload =
                    msgGenerator(simMessageId, sampleSize + sampleExtraSize)
                        .toWBytes()
                ErasureSample(messageId, sampleIndex, samplePayload)
            }

    override fun extend(msg: SourceMessage): SampledMessage {
        val origMessageSize = messageSizeRetriever(msg.blob.array)
        val origSimMessageId = messageSimIdRetriever(msg.blob.array)
        val originalSamplesCount = (origMessageSize - 1) / sampleSize + 1
        val extendedSamplesCount = originalSamplesCount * extensionFactor

        val headerPayload =
            msgGenerator(origSimMessageId, headerSize).toWBytes()
        val header =
            ErasureHeaderImpl(msg.topic, msg.messageId, extendedSamplesCount, originalSamplesCount, headerPayload)

        val erasureSamples = generateSamples(extendedSamplesCount, msg.messageId, origSimMessageId)

        return SampledMessageImpl(header, this, SamplesBoxImpl(erasureSamples.toMutableSet()))
    }

    override fun restore(sampledMessage: MutableSampledMessage): SourceMessage {
        require(sampledMessage.sampleBox.samples.size >= sampledMessage.header.recoverSampleCount)
        require(sampledMessage.sampleBox.samples.map { it.data }.distinct().size == 1)
        val sampleBytes = sampledMessage.sampleBox.samples.first().data
        val messsageSimId = messageSimIdRetriever(sampleBytes.array)
        val allSamples =
            generateSamples(
                sampledMessage.header.totalSampleCount,
                sampledMessage.header.messageId,
                messsageSimId
            ).toSet()
        val missingSamples = allSamples - sampledMessage.sampleBox.samples
        sampledMessage.sampleBox.addSamples(missingSamples)
        val msgBody = msgGenerator(messsageSimId, sampledMessage.header.recoverSampleCount * sampleSize)
        return SourceMessage(
            sampledMessage.header.topic,
            sampledMessage.header.messageId,
            msgBody.toWBytes()
        )
    }
}