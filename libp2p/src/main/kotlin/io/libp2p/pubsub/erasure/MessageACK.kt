package io.libp2p.pubsub.erasure

import io.libp2p.pubsub.MessageId
import kotlin.time.Duration

interface MessageACK {
    val messageId: MessageId
    val receivedSamplesCount: Int
    val lastPeerReceivedSampleIndex: SampleIndex
    val lastPeerReceivedSampleDuration: Duration

    // TODO maybe received samples bitmask or bloom
}