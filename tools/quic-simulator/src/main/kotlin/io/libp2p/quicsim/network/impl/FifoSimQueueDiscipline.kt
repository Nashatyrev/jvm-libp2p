package io.libp2p.quicsim.network.impl

import io.libp2p.quicsim.network.SimPacket
import io.libp2p.quicsim.network.SimQueueDiscipline
import java.util.ArrayDeque
import kotlin.math.ceil

class FifoSimQueueDiscipline(
    private val bandwidthBytesPerSecond: Long
) : SimQueueDiscipline {
    init {
        require(bandwidthBytesPerSecond > 0) { "bandwidthBytesPerSecond must be > 0" }
    }

    private data class QueuedPacket(
        val packet: SimPacket,
        val enqueueAtMillis: Long
    )

    private val queue = ArrayDeque<QueuedPacket>()
    private var nextDequeueAvailableMillis: Long = 0

    override var currentTimeMillis: Long = 0
        private set

    override val hasPendingPackets: Boolean
        get() = queue.isNotEmpty()

    override fun enqueue(packet: SimPacket): SimQueueDiscipline.EnqueueDecision {
        queue.addLast(QueuedPacket(packet, currentTimeMillis))
        return SimQueueDiscipline.EnqueueDecision.QUEUED
    }

    override fun advanceUntilDequeueOr(maxMillis: Long): List<SimPacket> {
        require(maxMillis >= currentTimeMillis) {
            "maxMillis must be >= currentTimeMillis"
        }

        if (queue.isEmpty()) {
            currentTimeMillis = maxMillis
            return emptyList()
        }

        val queued = queue.first()
        val transmitStart = maxOf(nextDequeueAvailableMillis, queued.enqueueAtMillis)
        val dequeueTime = transmitStart + serializationMillis(queued.packet.bytes)
        if (dequeueTime > maxMillis) {
            currentTimeMillis = maxMillis
            return emptyList()
        }

        currentTimeMillis = dequeueTime
        queue.removeFirst()
        nextDequeueAvailableMillis = dequeueTime
        return listOf(queued.packet)
    }

    private fun serializationMillis(bytes: Int): Long {
        return ceil(bytes.toDouble() * 1000.0 / bandwidthBytesPerSecond.toDouble()).toLong().coerceAtLeast(1L)
    }
}
