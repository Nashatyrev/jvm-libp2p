package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MessageACK
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import java.util.Random
import java.util.concurrent.CompletableFuture

abstract class AbstractMessagePeerHandler(
    val message: MutableSampledMessage,
    val peer: PeerId,
    val sender: ErasureSender,
    val random: Random
) {

    fun send(msg: ErasureMessage): CompletableFuture<Unit> =
        sender(peer, msg)
            .thenApply {
                onOutboundMessageSent(msg)
            }

    fun onInboundMessage(msg: ErasureMessage) {
        when(msg) {
            is ErasureHeader -> throw IllegalArgumentException("Header was not expected: $msg")
            is ErasureSample -> onInboundSample(msg)
            is MessageACK -> onInboundACK(msg)
        }
    }

    abstract val isComplete: Boolean

    abstract fun start()
    abstract fun onInboundSample(msg: ErasureSample)
    abstract fun onInboundACK(msg: MessageACK)
    abstract fun onOutboundMessageSent(msg: ErasureMessage)
    abstract fun onNewSamples(newSamples: Set<ErasureSample>)
}