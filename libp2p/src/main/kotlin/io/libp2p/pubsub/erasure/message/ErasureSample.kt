package io.libp2p.pubsub.erasure.message

import io.libp2p.pubsub.erasure.MessageId
import io.libp2p.pubsub.erasure.SampleIndex

interface ErasureSample {
    val messageId: MessageId
    val sampleIndex: SampleIndex
}