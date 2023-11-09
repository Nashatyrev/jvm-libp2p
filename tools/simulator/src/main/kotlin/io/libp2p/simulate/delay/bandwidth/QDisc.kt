package io.libp2p.simulate.delay.bandwidth

import io.libp2p.pubsub.gossip.CurrentTimeSupplier
import io.libp2p.simulate.SimPeerId
import kotlin.time.Duration.Companion.seconds

interface QDisc {

    fun enqueue(message: QDiscMessage)

    fun takeNext(): QDiscMessage

    fun isEmpty(): Boolean
}

interface QDiscMessage {
    val remotePeer: SimPeerId
    val size: Long
}

class SimpleQDisc : QDisc {
    private val queue = ArrayDeque<QDiscMessage>()
    override fun enqueue(message: QDiscMessage) { queue += message }
    override fun takeNext(): QDiscMessage = queue.removeFirst()
    override fun isEmpty(): Boolean = queue.isEmpty()
}

class FairQDisk(
    private val currentTimeSupplier: CurrentTimeSupplier
) : QDisc {

    private class PeerQueue {
        private val queue = ArrayDeque<QDiscMessage>()
        val throughputHistory = ThroughputTracker(1000, 5)

        fun nextSize() = throughputHistory.size + (queue.firstOrNull()?.size ?: 0)

        fun takeNext(curTime: Long): QDiscMessage {
            val message = queue.removeFirst()
            throughputHistory.add(curTime, message.size)
            return message
        }
        fun enqueue(message: QDiscMessage) { queue += message }
        fun isEmpty() = queue.isEmpty()
    }

    private class ThroughputTracker(
        private val slotPeriodMillis: Long,
        historySlotCount: Int
    ) {
        private data class Slot(
            val expiryTime: Long,
            var size: Long = 0
        )

        private val historyMilliseconds = slotPeriodMillis * historySlotCount
        private val historySlots = ArrayDeque<Slot>()
        private var sizeCache = 0L
        val size get() = sizeCache

        private fun prune(time: Long) {
            while (historySlots.isNotEmpty() && historySlots.first().expiryTime < time) {
                sizeCache -= historySlots.removeFirst().size
            }
        }

        fun add(time: Long, size: Long) {
            prune(time)
            val slotStartTime = time - time % slotPeriodMillis
            val slotExpireTime = slotStartTime + historyMilliseconds
            val needNewSlot = historySlots.isEmpty() || historySlots.last().expiryTime < slotExpireTime
            val slot = if (needNewSlot) {
                val newSlot = Slot(slotExpireTime)
                historySlots += newSlot
                newSlot
            } else {
                historySlots.last()
            }
            slot.size += size
            sizeCache += size
        }
    }

    private val peerQueues = mutableMapOf<SimPeerId, PeerQueue>()

    override fun enqueue(message: QDiscMessage) {
        val peerQueue = peerQueues.computeIfAbsent(message.remotePeer) { PeerQueue() }
        peerQueue.enqueue(message)
    }

    override fun takeNext(): QDiscMessage {
        // taking a message from the peer queue with minimum effective throughput
        val (_, peerQueue) = (peerQueues
            .filter { !it.value.isEmpty() }
            .minByOrNull { it.value.nextSize() }
            ?: throw NoSuchElementException())
        return peerQueue.takeNext(currentTimeSupplier())
    }

    override fun isEmpty(): Boolean =
        peerQueues.all { it.value.isEmpty() }
}