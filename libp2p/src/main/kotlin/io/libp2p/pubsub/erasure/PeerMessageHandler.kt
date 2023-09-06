package io.libp2p.pubsub.erasure

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.message.MessageACK
import io.libp2p.pubsub.erasure.message.SampledMessage
import java.util.BitSet

abstract class PeerMessageHandler(
    val sampledMessage: SampledMessage,
    val targetPeer: PeerId
) {
    abstract fun onSampleReceived(sampleIndex: SampleIndex)
    abstract fun onACKReceived(ack: MessageACK)
}

abstract class PeerSamplesStreamer(
    sampledMessage: SampledMessage,
    targetPeer: PeerId
) : PeerMessageHandler(sampledMessage, targetPeer) {

    val sentSamples = BitSet(sampledMessage.header.totalSampleCount)
}

abstract class PeerACKSender(
    sampledMessage: SampledMessage,
    targetPeer: PeerId
) : PeerMessageHandler(sampledMessage, targetPeer) {

}
