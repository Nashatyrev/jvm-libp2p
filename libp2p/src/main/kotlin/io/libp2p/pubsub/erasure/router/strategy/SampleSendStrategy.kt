package io.libp2p.pubsub.erasure.router.strategy

import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.MessageACK

interface SampleSendStrategy {

    fun hasToSend(): Boolean

    fun onSent()

    fun onInboundACK(msg: MessageACK)

    fun onOutboundMessageSent(msg: ErasureMessage)

    companion object {
        fun sendAll() =
            object : AbstractSampleSendStrategy(Int.MAX_VALUE) {
                override fun onInboundACK(msg: MessageACK) {}
                override fun onOutboundMessageSent(msg: ErasureMessage) {}
            }

        fun cWndStrategy(windowSize: Int) =
            object : AbstractSampleSendStrategy(windowSize) {
                var sentCount = 0

                override fun onSent() {
                    super.onSent()
                    sentCount++
                }

                override fun onInboundACK(msg: MessageACK) {
                    val ackedCount = msg.peerReceivedSamplesCount
                    samplesToSend = windowSize - (sentCount - ackedCount)
                }
                override fun onOutboundMessageSent(msg: ErasureMessage) {}
            }
    }
}