package io.libp2p.simulate.delay.bandwidth

import io.libp2p.simulate.Bandwidth
import io.libp2p.simulate.BandwidthDelayer
import io.libp2p.simulate.SimPeerId
import io.libp2p.tools.schedule
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService

class QDiscBandwidthTracker(
    override val totalBandwidth: Bandwidth,
    val executor: ScheduledExecutorService,
    private val qDisc: QDisc
) : BandwidthDelayer {

    private var messageInProgress: Message? = null

    private inner class Message(
        override val remotePeer: SimPeerId,
        override val size: Long
    ) : QDiscMessage {
        val promise = CompletableFuture<Unit>()
    }


    private fun enqueue(msg: Message) {
        qDisc.enqueue(msg)
        if (messageInProgress == null) {
            processNextMessage()
        }
    }

    private fun messageProcessed() {
        check(messageInProgress != null)
        messageInProgress!!.promise.complete(Unit)
        messageInProgress = null
        processNextMessage()
    }

    private fun processNextMessage() {
        if (qDisc.isEmpty()) {
            return
        } else {
            val nextMessage = qDisc.takeNext() as Message
            messageInProgress = nextMessage
            val transmitTime = totalBandwidth.getTransmitTime(nextMessage.size)
            executor.schedule(transmitTime) {
                messageProcessed()
            }
        }
    }

    override fun delay(remotePeer: SimPeerId, messageSize: Long): CompletableFuture<Unit> {
        val message = Message(remotePeer, messageSize)
        enqueue(message)
        return message.promise
    }
}
