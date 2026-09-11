package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.CodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FqCodelUdpSimBandwidthQueue
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * Packets travelling through the simulator carry a reference counted buffer and the simulator holds their last
 * reference, so a packet dropped by a queue discipline must be released by that queue.
 */
class UdpSimBandwidthQueueLeakTest {

    @Test
    fun `fifo releases the packets it drops`() =
        assertOverloadedQueueReleasesDroppedPackets(
            FifoUdpSimBandwidthQueue(Bandwidth(1_000), maxQueueWaitTime = 50.milliseconds)
        )

    @Test
    fun `fq codel releases the packets it drops`() =
        assertOverloadedQueueReleasesDroppedPackets(
            FqCodelUdpSimBandwidthQueue(Bandwidth(1_000), maxQueueWaitTime = 50.milliseconds)
        )

    @Test
    fun `codel releases the packets it drops`() =
        assertOverloadedQueueReleasesDroppedPackets(
            CodelUdpSimBandwidthQueue(Bandwidth(1_000), targetDelay = 1.milliseconds)
        )

    @Test
    fun `codel releases the packets dropped on overflow`() {
        val queue = CodelUdpSimBandwidthQueue(Bandwidth(1_000), limitPackets = 2)
        val packets = (1..10).map { udpSimDatagram(100, "a", "b") }

        queue.receivePackets(packets)

        assertEquals(listOf(0), packets.drop(2).map { it.refCnt() }.distinct())
    }

    /**
     * Pushes way more traffic into [queue] than its bandwidth can carry, drains it completely and then asserts
     * that every packet which didn't make it out was released.
     */
    private fun assertOverloadedQueueReleasesDroppedPackets(queue: UdpSimBandwidthQueue) {
        val packets = (1..50).map { udpSimDatagram(100, "a", "b") }
        queue.receivePackets(packets)

        val emitted = drainCompletely(queue)
        val dropped = packets.filter { packet -> emitted.none { it === packet } }
        assertTrue(dropped.isNotEmpty()) { "the queue was expected to drop packets, but emitted them all" }

        assertEquals(listOf(0), dropped.map { it.refCnt() }.distinct())
        assertEquals(listOf(1), emitted.map { it.refCnt() }.distinct())
        emitted.forEach { it.release() }
    }

    private fun drainCompletely(queue: UdpSimBandwidthQueue): List<DatagramPacket> {
        val emitted = mutableListOf<DatagramPacket>()
        repeat(MAX_DRAIN_STEPS) {
            emitted += queue.emitPackets()
            val nextTaskDuration = queue.nextTaskDuration() ?: return emitted
            queue.advanceAndExecuteAll(nextTaskDuration)
        }
        throw IllegalStateException("The queue was not drained in $MAX_DRAIN_STEPS steps")
    }

    companion object {
        private const val MAX_DRAIN_STEPS = 1_000
    }
}
