package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.FqCodelUdpSimBandwidthQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class FqCodelUdpSimBandwidthQueueTest {
    @Test
    fun `spaces packets according to bandwidth`() {
        val bandwidthQueue = FqCodelUdpSimBandwidthQueue(Bandwidth(1_000))
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")

        assertEquals(listOf(packet1), bandwidthQueue.deliver(listOf(packet1, packet2)))
        assertEquals(100.milliseconds, bandwidthQueue.nextTaskDuration())

        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packet2), bandwidthQueue.deliver(emptyList()))
        assertEquals(null, bandwidthQueue.nextTaskDuration())
    }

    @Test
    fun `interleaves active flows`() {
        val bandwidthQueue = FqCodelUdpSimBandwidthQueue(
            bandwidth = Bandwidth(1_000),
            maxQueueWaitTime = 1_000.milliseconds,
            targetDelay = 1_000.milliseconds
        )
        val flowA1 = UdpSimPacket(1, 100, "a", "z")
        val flowA2 = UdpSimPacket(2, 100, "a", "z")
        val flowB1 = UdpSimPacket(3, 100, "b", "z")
        val flowB2 = UdpSimPacket(4, 100, "b", "z")

        assertEquals(listOf(flowA1), bandwidthQueue.deliver(listOf(flowA1, flowA2, flowB1, flowB2)))
        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(flowB1), bandwidthQueue.deliver(emptyList()))
        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(flowA2), bandwidthQueue.deliver(emptyList()))
        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(flowB2), bandwidthQueue.deliver(emptyList()))
    }
}
