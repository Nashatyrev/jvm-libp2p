package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.message.ErasureMessage

interface MessageRouter {

    val isComplete: Boolean

    fun start()

    fun onMessage(msg: ErasureMessage, from: PeerId)
}