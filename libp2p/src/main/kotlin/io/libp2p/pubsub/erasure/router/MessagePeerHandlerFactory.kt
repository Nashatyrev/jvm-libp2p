package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SampledMessage
import java.util.Random

abstract class MessagePeerHandlerFactory {

    lateinit var sender: ErasureSender
    lateinit var random: Random

    abstract fun create(message: MutableSampledMessage, peer: PeerId): AbstractMessagePeerHandler
}