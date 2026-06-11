package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.milliseconds

class FifoUdpSimBandwidthQueueTest {

    @Test
    fun `idle bandwidth queue stage delivers first packet immediately`() {
        val bandwidthQueue = FifoUdpSimBandwidthQueue(Bandwidth(1_000))
        val packet = UdpSimPacket(1, 100, "a", "b")

        assertEquals(listOf(packet), bandwidthQueue.deliver(listOf(packet)))
        assertEquals(null, bandwidthQueue.nextTaskDuration())
    }

    @Test
    fun `spaces packets after immediate first packet`() {
        val bandwidthQueue = FifoUdpSimBandwidthQueue(Bandwidth(1_000))
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")
        val packet3 = UdpSimPacket(3, 100, "a", "b")

        assertEquals(listOf(packet1), bandwidthQueue.deliver(listOf(packet1, packet2)))
        assertEquals(100.milliseconds, bandwidthQueue.nextTaskDuration())

        bandwidthQueue.advanceAndExecuteAll(50.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), bandwidthQueue.deliver(listOf(packet3)))
        assertEquals(50.milliseconds, bandwidthQueue.nextTaskDuration())

        bandwidthQueue.advanceAndExecuteAll(50.milliseconds)
        assertEquals(listOf(packet2), bandwidthQueue.deliver(emptyList()))
        assertEquals(100.milliseconds, bandwidthQueue.nextTaskDuration())

        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packet3), bandwidthQueue.deliver(emptyList()))
    }

    @Test
    fun `spaces non-empty packets by at least one nanosecond`() {
        val bandwidthQueue = FifoUdpSimBandwidthQueue(Bandwidth(1_000))
        val packet1 = UdpSimPacket(1, 10, "a", "b")
        val packet2 = UdpSimPacket(2, 10, "a", "b")
        val packet3 = UdpSimPacket(3, 10, "a", "b")

        assertEquals(listOf(packet1), bandwidthQueue.deliver(listOf(packet1)))
        bandwidthQueue.advanceAndExecuteAll(1.nanoseconds)
        assertEquals(emptyList<UdpSimPacket>(), bandwidthQueue.deliver(listOf(packet2)))
        bandwidthQueue.advanceAndExecuteAll(1.nanoseconds)
        assertEquals(emptyList<UdpSimPacket>(), bandwidthQueue.deliver(listOf(packet3)))

        bandwidthQueue.advanceAndExecuteAll(10.milliseconds - 2.nanoseconds)
        assertEquals(listOf(packet2), bandwidthQueue.deliver(emptyList()))
        assertEquals(10.milliseconds, bandwidthQueue.nextTaskDuration())

        bandwidthQueue.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packet3), bandwidthQueue.deliver(emptyList()))
        assertEquals(null, bandwidthQueue.nextTaskDuration())
    }
}
