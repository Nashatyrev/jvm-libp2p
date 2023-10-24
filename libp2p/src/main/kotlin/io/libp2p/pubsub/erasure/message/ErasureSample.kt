package io.libp2p.pubsub.erasure.message

import io.libp2p.etc.types.WBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.erasure.SampleIndex

data class ErasureSample(
    override val messageId: MessageId,
    val sampleIndex: SampleIndex,
    val data: WBytes
) : ErasureMessage {

    override fun equals(other: Any?): Boolean {
        other as ErasureSample
        return messageId == other.messageId && sampleIndex == other.sampleIndex
    }

    override fun hashCode() =
        31 * messageId.hashCode() + sampleIndex
}