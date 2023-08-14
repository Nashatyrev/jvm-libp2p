package io.libp2p.pubsub.erasure

import io.libp2p.core.PeerId

abstract class SamplesBroadcaster(
    val erasureHeader: ErasureHeader,
    val targetPeers: List<PeerId>
) {

    abstract fun onSampleReceived(from: PeerId, sampleIndex: SampleIndex)
    abstract fun onACKReceived(from: PeerId, ack: MessageACK)

    abstract fun addSample(sample: ErasureSample)
}