package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampleBoxObserver
import io.libp2p.pubsub.erasure.message.SampledMessage
import java.util.Random

class RelayingMessageRouter(
    val sender: ErasureSender,
    val peers: List<PeerId>,
    val message: MutableSampledMessage,
    val random: Random,
    val messagePeerHandlerFactory: MessagePeerHandlerFactory
) : MessageRouter {

    val peerHandlers: Map<PeerId, AbstractMessagePeerHandler>

    init {
        messagePeerHandlerFactory.random = random
        messagePeerHandlerFactory.sender = sender
        message.sampleBox.observers += SampleBoxObserver { _, newSamples -> onNewSamples(newSamples)}
        peerHandlers = peers.associateWith { messagePeerHandlerFactory.create(message, it) }
    }

    override val isComplete: Boolean
        get() = peerHandlers.values.all { it.isComplete }

    override fun start() {
        peerHandlers.values.forEach {
            it.start()
        }
    }

    override fun onMessage(msg: ErasureMessage, from: PeerId) {
        peerHandlers[from]?.onInboundMessage(msg)
    }

    fun onNewSamples(newSamples: Set<ErasureSample>) {
        peerHandlers.values.forEach { it.onNewSamples(newSamples) }
    }
}