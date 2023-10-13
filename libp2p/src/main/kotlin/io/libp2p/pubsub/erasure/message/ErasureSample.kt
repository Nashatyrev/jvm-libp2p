package io.libp2p.pubsub.erasure.message

import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.erasure.SampleIndex

interface ErasureSample : ErasureMessage {
    override val messageId: MessageId
    val sampleIndex: SampleIndex
}