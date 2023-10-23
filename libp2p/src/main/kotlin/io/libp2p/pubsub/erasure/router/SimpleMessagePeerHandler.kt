package io.libp2p.pubsub.erasure.router

import io.libp2p.core.PeerId
import io.libp2p.pubsub.erasure.ErasureSender
import io.libp2p.pubsub.erasure.SampleIndex
import io.libp2p.pubsub.erasure.message.ErasureHeader
import io.libp2p.pubsub.erasure.message.ErasureMessage
import io.libp2p.pubsub.erasure.message.ErasureSample
import io.libp2p.pubsub.erasure.message.MessageACK
import io.libp2p.pubsub.erasure.message.MutableSampledMessage
import io.libp2p.pubsub.erasure.message.SourceMessage
import io.libp2p.pubsub.erasure.message.isComplete
import io.libp2p.pubsub.erasure.message.plusAssign
import io.libp2p.pubsub.erasure.router.strategy.AckSendStrategy
import io.libp2p.pubsub.erasure.router.strategy.SampleSendStrategy
import java.util.Random

class SimpleMessagePeerHandler(
    message: MutableSampledMessage,
    peer: PeerId,
    sender: ErasureSender,
    random: Random,
    private val ackSendStrategy: AckSendStrategy,
    private val sampleSendStrategy: SampleSendStrategy
) :
    AbstractMessagePeerHandler(message, peer, sender, random) {

    var lastACK = MessageACK(message.header.messageId, 0, 0)

    val needMoreSending get() =
        !(lastACK.hasSamplesCount >= message.header.recoverSampleCount
                || sentSampleIndices.size >= message.header.recoverSampleCount)
    val needMoreReceiving get() = !message.isComplete()

    val sentSampleIndices = mutableSetOf<SampleIndex>()
    val receivedSampleIndices = mutableSetOf<SampleIndex>()

    var headerSent = false
    var headerReceived = false
    val remoteKnowsMessage get() =
        headerSent || headerReceived || receivedSampleIndices.isNotEmpty() || lastACK.hasSamplesCount > 0

    private fun takeNextRandomSampleToSend(): ErasureSample? {
        val candidates = message.sampleBox.samples
            .filter { it.sampleIndex !in receivedSampleIndices }
            .filter { it.sampleIndex !in sentSampleIndices }
        return if (candidates.isEmpty()) {
            null
        } else {
            val sample = candidates[random.nextInt(candidates.size)]
            sentSampleIndices += sample.sampleIndex
            sample
        }
    }

    private fun sendNextSample(): Boolean {
        return when {
            !needMoreSending -> false
            else -> {
                val sample = takeNextRandomSampleToSend()
                sample?.also {
                    send(sample)
                } != null
            }
        }
    }

    private fun maybeSendHeader() {
        if (!remoteKnowsMessage) {
            headerSent = true
            send(message.header)
        }
    }

    private fun sendAck() {
        maybeSendHeader()
        send(MessageACK(message.header.messageId, message.sampleBox.samples.size, receivedSampleIndices.size))
    }

    private fun maybeSendSamples() {
        while (sampleSendStrategy.hasToSend()) {
            maybeSendHeader()
            if (sendNextSample()) {
                sampleSendStrategy.onSent()
            } else {
                break
            }
        }
    }

    override val isComplete: Boolean
        get() = !(needMoreSending || needMoreReceiving)

    override fun start() {
        maybeSendSamples()
    }

    override fun onInboundSample(msg: ErasureSample) {
        message.sampleBox += msg
        receivedSampleIndices += msg.sampleIndex
        if (ackSendStrategy.onInboundSample(msg)) {
            sendAck()
        }
    }

    override fun onInboundACK(msg: MessageACK) {
        lastACK = msg
        sampleSendStrategy.onInboundACK(msg)
        maybeSendSamples()
    }

    override fun onInboundHeader(msg: ErasureHeader) {
        require(msg == message.header)
        headerReceived
    }

    override fun onOutboundMessageSent(msg: ErasureMessage) {
        sampleSendStrategy.onOutboundMessageSent(msg)
        maybeSendSamples()
    }

    override fun onNewSamples(newSamples: Set<ErasureSample>) {
        if (ackSendStrategy.onNewSamples(newSamples)) {
            sendAck()
        }
        maybeSendSamples()
    }
}