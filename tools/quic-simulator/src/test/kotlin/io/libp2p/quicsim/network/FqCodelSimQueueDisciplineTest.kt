package io.libp2p.quicsim.network

import io.libp2p.quicsim.network.impl.FqCodelSimQueueDiscipline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FqCodelSimQueueDisciplineTest {

    @Test
    fun `fq-codel interleaves packets from different flows`() {
        val qdisc = FqCodelSimQueueDiscipline(
            bandwidthBytesPerSecond = 1_000,
            quantumBytes = 200,
            targetMillis = 1_000,
            intervalMillis = 10_000
        )

        val packets = listOf(
            SimPacket(1, 100, "a", "x"),
            SimPacket(2, 100, "b", "y"),
            SimPacket(3, 100, "a", "x"),
            SimPacket(4, 100, "b", "y")
        )
        packets.forEach { qdisc.enqueue(it) }

        val out = mutableListOf<Long>()
        while (qdisc.hasPendingPackets) {
            val p = qdisc.advanceUntilDequeueOr(10_000)
            if (p.isNotEmpty()) {
                out += p.first().id
            }
        }

        assertEquals(listOf(1L, 2L, 3L, 4L), out)
    }

    @Test
    fun `fq-codel drops some packets under persistent sojourn`() {
        val qdisc = FqCodelSimQueueDiscipline(
            bandwidthBytesPerSecond = 10,
            quantumBytes = 10_000,
            targetMillis = 5,
            intervalMillis = 20
        )

        repeat(50) {
            qdisc.enqueue(SimPacket((it + 1).toLong(), 100, "src", "dst"))
        }

        val dequeued = mutableListOf<SimPacket>()
        while (qdisc.hasPendingPackets) {
            dequeued += qdisc.advanceUntilDequeueOr(500_000)
        }

        assertTrue(dequeued.size < 50, "Expected at least some drops with CoDel under persistent queue delay")
        assertTrue(dequeued.isNotEmpty(), "Expected some packets still delivered")
    }
}
