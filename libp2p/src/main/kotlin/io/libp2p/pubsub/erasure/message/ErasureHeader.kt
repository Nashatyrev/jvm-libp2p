package io.libp2p.pubsub.erasure.message

import io.libp2p.pubsub.Topic
import io.libp2p.pubsub.erasure.MessageId
import io.libp2p.pubsub.erasure.SampleIndex

interface ErasureHeader {
    val topic: Topic
    val messageId: MessageId
    val totalSampleCount: SampleIndex
    val recoverSampleCount: SampleIndex
}