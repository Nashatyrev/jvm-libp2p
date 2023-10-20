package io.libp2p.pubsub.erasure.message

import io.libp2p.pubsub.MessageId

data class MessageACK(
    override val messageId: MessageId,
    val hasSamplesCount: Int,
    val peerReceivedSamplesCount: Int
//    val lastPeerReceivedSampleIndex: SampleIndex?,
//    val lastPeerReceivedSampleDuration: Duration?
) : ErasureMessage {
    // TODO maybe received samples bitmask or bloom
}