package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimQueueDiscipline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class FifoUdpSimQueueDisciplineTest {

    @Test
    fun `dequeues packets in fifo order after service time and latency`() {
        val qdisc = FifoUdpSimQueueDiscipline(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds
        )
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(listOf(packet1, packet2)))
        assertEquals(110.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(109.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(emptyList()))

        qdisc.advanceAndExecuteAll(1.milliseconds)
        assertEquals(listOf(packet1), qdisc.deliver(emptyList()))
        assertEquals(100.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packet2), qdisc.deliver(emptyList()))
        assertEquals(null, qdisc.nextTaskDuration())
    }

    @Test
    fun `drops packets exceeding max queue wait time`() {
        val qdisc = FifoUdpSimQueueDiscipline(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds,
            maxQueueWaitTime = 150.milliseconds
        )
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(listOf(packet1, packet2)))
        assertEquals(110.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(110.milliseconds)
        assertEquals(listOf(packet1), qdisc.deliver(emptyList()))
        assertEquals(null, qdisc.nextTaskDuration())
    }

    @Test
    fun `accounts for current queue backlog when enqueueing later packets`() {
        val qdisc = FifoUdpSimQueueDiscipline(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds
        )
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")

        qdisc.deliver(listOf(packet1))
        qdisc.advanceAndExecuteAll(50.milliseconds)
        qdisc.deliver(listOf(packet2))

        assertEquals(60.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(60.milliseconds)
        assertEquals(listOf(packet1), qdisc.deliver(emptyList()))
        assertEquals(100.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packet2), qdisc.deliver(emptyList()))
    }

    @Test
    fun `dequeues after service time when latency is zero`() {
        val qdisc = FifoUdpSimQueueDiscipline(
            bandwidth = Bandwidth(1_000),
            latency = ZERO
        )
        val packet = UdpSimPacket(1, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(listOf(packet)))
        assertEquals(100.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(99.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(emptyList()))

        qdisc.advanceAndExecuteAll(1.milliseconds)
        assertEquals(listOf(packet), qdisc.deliver(emptyList()))
        assertEquals(null, qdisc.nextTaskDuration())
    }

    @Test
    fun `zero-length packet is delivered after latency without shaping delay`() {
        val qdisc = FifoUdpSimQueueDiscipline(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds
        )
        val packet = UdpSimPacket(1, 0, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(listOf(packet)))
        assertEquals(10.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packet), qdisc.deliver(emptyList()))
        assertEquals(null, qdisc.nextTaskDuration())
    }
}
