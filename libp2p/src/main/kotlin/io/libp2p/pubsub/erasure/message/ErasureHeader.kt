package io.libp2p.pubsub.erasure.message

import io.libp2p.etc.types.WBytes
import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.erasure.SampleIndex

interface ErasureHeader : ErasureMessage {
    val topic: Topic
    override val messageId: MessageId
    val totalSampleCount: SampleIndex
    val recoverSampleCount: SampleIndex
    val payload: WBytes
}