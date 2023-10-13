package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.SampledMessage
import java.util.Random
import java.util.concurrent.CompletableFuture

class MessagePeerHandler(
    val message: SampledMessage,
    val peer: PeerId,
    val sender: ErasureSender,
    val random: Random
) {

    fun send(msg: ErasureMessage): CompletableFuture<Unit> = sender(peer, msg)

    fun onMessage(msg: ErasureMessage) {

    }

    fun onNewSamples(newSamples: Set<ErasureSample>) {

    }
}