package io.libp2p.quicsim.network.impl

import io.libp2p.quicsim.network.SimPacket
import io.libp2p.quicsim.network.SimQueueDiscipline
import java.util.ArrayDeque

class FifoSimQueueDiscipline : SimQueueDiscipline {
    private val queue = ArrayDeque<SimPacket>()

    override var currentTimeMillis: Long = 0
        private set

    override fun enqueue(packet: SimPacket): SimQueueDiscipline.EnqueueDecision {
        queue.addLast(packet)
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

        val packet = queue.removeFirst()
        return listOf(packet)
    }
}
