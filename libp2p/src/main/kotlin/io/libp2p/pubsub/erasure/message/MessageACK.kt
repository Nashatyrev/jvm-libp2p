package io.libp2p.pubsub.erasure.message

import io.libp2p.pubsub.MessageId
import io.libp2p.pubsub.erasure.SampleIndex
import kotlin.time.Duration

interface MessageACK {
    val messageId: MessageId
    val receivedSamplesCount: Int
    val lastPeerReceivedSampleIndex: SampleIndex
    val lastPeerReceivedSampleDuration: Duration

    // TODO maybe received samples bitmask or bloom
}