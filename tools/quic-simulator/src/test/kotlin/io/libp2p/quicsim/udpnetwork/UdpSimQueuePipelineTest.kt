package io.libp2p.quicsim.udpnetwork

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class UdpSimQueuePipelineTest {

    @Test
    fun `dequeues packets in fifo order after service time and latency`() {
        val qdisc = fifoUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds
        )
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(listOf(packet1, packet2)))
        assertEquals(10.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(9.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(emptyList()))

        qdisc.advanceAndExecuteAll(1.milliseconds)
        assertEquals(listOf(packet1), qdisc.deliver(emptyList()))
        assertEquals(90.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(90.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(emptyList()))
        assertEquals(10.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packet2), qdisc.deliver(emptyList()))
        assertEquals(null, qdisc.nextTaskDuration())
    }

    @Test
    fun `drops packets exceeding max queue wait time`() {
        val qdisc = fifoUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds,
            maxQueueWaitTime = 50.milliseconds
        )
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(listOf(packet1, packet2)))
        assertEquals(10.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packet1), qdisc.deliver(emptyList()))
        assertEquals(null, qdisc.nextTaskDuration())
    }

    @Test
    fun `idle bandwidth queue delivers first packet immediately when latency is zero`() {
        val qdisc = fifoUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = ZERO
        )
        val packet = UdpSimPacket(1, 100, "a", "b")

        assertEquals(listOf(packet), qdisc.deliver(listOf(packet)))
        assertEquals(null, qdisc.nextTaskDuration())
    }

    @Test
    fun `zero-length packet is delivered after latency without shaping delay`() {
        val qdisc = fifoUdpSimQueue(
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

    @Test
    fun `can apply latency before bandwidth queue`() {
        val qdisc = latencyThenBandwidthUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds
        )
        val packet = UdpSimPacket(1, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(listOf(packet)))
        assertEquals(10.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packet), qdisc.deliver(emptyList()))
        assertEquals(null, qdisc.nextTaskDuration())
    }

    @Test
    fun `keeps later packet behind previously scheduled latency`() {
        val qdisc = fifoUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = 200.milliseconds
        )
        val packet1 = UdpSimPacket(1, 100, "a", "b")

        qdisc.deliver(listOf(packet1))
        assertEquals(200.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(150.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), qdisc.deliver(emptyList()))
        assertEquals(50.milliseconds, qdisc.nextTaskDuration())

        val packet2 = UdpSimPacket(2, 100, "a", "b")

        qdisc.deliver(listOf(packet2))

        qdisc.advanceAndExecuteAll(50.milliseconds)
        assertEquals(listOf(packet1), qdisc.deliver(emptyList()))

        assertEquals(150.milliseconds, qdisc.nextTaskDuration())
    }
}
