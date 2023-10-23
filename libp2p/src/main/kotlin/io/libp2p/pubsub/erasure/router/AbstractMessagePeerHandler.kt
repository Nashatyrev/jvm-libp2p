package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MessageACK
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage
import java.util.Random
import java.util.concurrent.CompletableFuture

abstract class AbstractMessagePeerHandler(
    val message: MutableSampledMessage,
    val peer: PeerId,
    val sender: ErasureSender,
    val random: Random
) {

    fun send(msgs: List<ErasureMessage>): CompletableFuture<Unit> {
        val promise = sender(peer, msgs)
        msgs.forEach { onOutboundMessageSent(it) }
        return promise
    }
    fun send(msg: ErasureMessage): CompletableFuture<Unit> = send(listOf(msg))

    fun onInboundMessage(msg: ErasureMessage) {
        when (msg) {
            is ErasureHeader -> onInboundHeader(msg)
            is ErasureSample -> onInboundSample(msg)
            is MessageACK -> onInboundACK(msg)
        }
    }

    abstract val isComplete: Boolean

    abstract fun start()
    abstract fun onInboundHeader(msg: ErasureHeader)
    abstract fun onInboundSample(msg: ErasureSample)
    abstract fun onInboundACK(msg: MessageACK)
    abstract fun onOutboundMessageSent(msg: ErasureMessage)
    abstract fun onNewSamples(newSamples: Set<ErasureSample>)
}