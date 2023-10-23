package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MessageACK
import io.libp2p.pubsub.erasure.message.SampleBoxObserver
import io.libp2p.pubsub.erasure.message.SampledMessage

class PublishingMessageRouter(
    val sender: ErasureSender,
    val peers: List<PeerId>,
    val message: SampledMessage
) : MessageRouter {

    init {
        // all samples should be available
        require(message.sampleBox.samples.size == message.header.totalSampleCount)
    }

    override var isComplete: Boolean = false

    override fun onMessage(msg: ErasureMessage, from: PeerId) {
        when(msg) {
            is MessageACK -> {
                TODO()
            }
            else -> { /*ignore*/ }
        }
    }

    override fun start() {
        peers.forEach { peer ->
            sender(
                peer,
                listOf(
                    message.header,
                    MessageACK(message.header.messageId, message.header.totalSampleCount, 0)
                )
            )
        }
        // TODO()
    }
}