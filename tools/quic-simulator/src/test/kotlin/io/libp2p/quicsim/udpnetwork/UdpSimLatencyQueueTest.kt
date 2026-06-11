package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.UdpSimLatencyQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class UdpSimLatencyQueueTest {

    @Test
    fun `latency delay stage delivers packets after latency`() {
        val latencyDelay = UdpSimLatencyQueue(10.milliseconds)
        val packet = UdpSimPacket(1, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), latencyDelay.deliver(listOf(packet)))
        assertEquals(10.milliseconds, latencyDelay.nextTaskDuration())

        latencyDelay.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packet), latencyDelay.deliver(emptyList()))
        assertEquals(null, latencyDelay.nextTaskDuration())
    }

    @Test
    fun `latency ahead processor can read packets before normal processor time advances`() {
        val latencyDelay = UdpSimLatencyQueue(100.milliseconds)
        val ahead = latencyDelay.aheadProcessor
        val packet = UdpSimPacket(1, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), latencyDelay.deliver(listOf(packet)))
        assertEquals(100.milliseconds, latencyDelay.nextTaskDuration())

        ahead.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packet), ahead.deliver(emptyList()))

        assertEquals(null, latencyDelay.nextTaskDuration())
        assertEquals(emptyList<UdpSimPacket>(), latencyDelay.deliver(emptyList()))
    }

    @Test
    fun `latency ahead processor can write packets before normal processor time advances`() {
        val latencyDelay = UdpSimLatencyQueue(100.milliseconds)
        val ahead = latencyDelay.aheadProcessor
        val packet = UdpSimPacket(1, 100, "a", "b")

        ahead.advanceAndExecuteAll(50.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), ahead.deliver(listOf(packet)))

        assertEquals(150.milliseconds, latencyDelay.nextTaskDuration())
        latencyDelay.advanceAndExecuteAll(149.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), latencyDelay.deliver(emptyList()))

        latencyDelay.advanceAndExecuteAll(1.milliseconds)
        assertEquals(listOf(packet), latencyDelay.deliver(emptyList()))
        assertEquals(null, latencyDelay.nextTaskDuration())
    }

    @Test
    fun `latency ahead processor cannot advance past latency bound`() {
        val latencyDelay = UdpSimLatencyQueue(100.milliseconds)
        val ahead = latencyDelay.aheadProcessor

        assertThrows(IllegalArgumentException::class.java) {
            ahead.advanceAndExecuteAll(101.milliseconds)
        }
    }
}
