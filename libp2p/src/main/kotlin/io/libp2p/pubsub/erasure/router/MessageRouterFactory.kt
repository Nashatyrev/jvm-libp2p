package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.SampledMessage

interface MessageRouterFactory {
    var sender: ErasureSender

    fun create(
        message: SampledMessage,
        peers: List<PeerId>
    ): MessageRouter
}