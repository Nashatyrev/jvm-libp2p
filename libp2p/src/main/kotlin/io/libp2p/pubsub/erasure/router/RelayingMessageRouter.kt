package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MessageACK
import io.libp2p.pubsub.erasure.message.SampleBoxObserver
import io.libp2p.pubsub.erasure.message.SampledMessage
import java.util.Random

class RelayingMessageRouter(
    val sender: ErasureSender,
    val peers: List<PeerId>,
    val message: SampledMessage,
    val random: Random
) : MessageRouter {

    val peerHandlers = peers.associateWith { MessagePeerHandler(message, it, sender, random) }

    init {
        message.sampleBox.observers += SampleBoxObserver { _, newSamples -> onNewSamples(newSamples)}
    }

    override var isComplete = false

    override fun onMessage(msg: ErasureMessage, from: PeerId) {
        peerHandlers[from]?.onMessage(msg)
    }

    fun onNewSamples(newSamples: Set<ErasureSample>) {
        peerHandlers.values.forEach { it.onNewSamples(newSamples) }
    }

    override fun publish() {
        // ??
    }
}