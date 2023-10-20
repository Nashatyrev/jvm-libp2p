package io.libp2p.pubsub.erasure.message.impl

import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.erasure.SampleIndex
import io.libp2p.pubsub.erasure.message.ErasureHeader

data class ErasureHeaderImpl(
    override val topic: Topic,
    override val messageId: MessageId,
    override val totalSampleCount: SampleIndex,
    override val recoverSampleCount: SampleIndex
) : ErasureHeader {
}