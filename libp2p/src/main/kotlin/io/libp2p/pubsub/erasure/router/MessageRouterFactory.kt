package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import io.libp2p.pubsub.erasure.message.isComplete
import java.util.Random

abstract class MessageRouterFactory {

    lateinit var sender: ErasureSender

    abstract fun create(
        message: MutableSampledMessage,
        peers: List<PeerId>
    ): MessageRouter
}
