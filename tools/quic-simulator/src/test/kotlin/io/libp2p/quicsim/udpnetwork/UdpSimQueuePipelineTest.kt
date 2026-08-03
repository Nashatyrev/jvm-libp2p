package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.deliver
import io.netty.channel.socket.DatagramPacket
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
        val packet1 = udpSimDatagram(100, "a", "b")
        val packet2 = udpSimDatagram(100, "a", "b")

        assertEquals(emptyList<DatagramPacket>(), qdisc.deliver(listOf(packet1, packet2)))
        assertEquals(100.milliseconds, qdisc.nextTaskDuration())
        assertEquals(10.milliseconds, qdisc.latencyQueue.emitter.nextTaskDuration())

        qdisc.latencyQueue.emitter.advanceAndExecuteAll(9.milliseconds)
        assertEquals(emptyList<DatagramPacket>(), qdisc.latencyQueue.emitter.emitPackets())

        qdisc.latencyQueue.emitter.advanceAndExecuteAll(1.milliseconds)
        assertEquals(listOf(packet1), qdisc.latencyQueue.emitter.emitPackets())
        assertEquals(100.milliseconds, qdisc.nextTaskDuration())

        qdisc.advanceAndExecuteAll(100.milliseconds)
        assertEquals(emptyList<DatagramPacket>(), qdisc.deliver(emptyList()))
        assertEquals(null, qdisc.nextTaskDuration())
        assertEquals(100.milliseconds, qdisc.latencyQueue.emitter.nextTaskDuration())

        qdisc.latencyQueue.emitter.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packet2), qdisc.latencyQueue.emitter.emitPackets())
        assertEquals(null, qdisc.latencyQueue.emitter.nextTaskDuration())
    }

    @Test
    fun `drops packets exceeding max queue wait time`() {
        val qdisc = fifoUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds,
            maxQueueWaitTime = 50.milliseconds
        )
        val packet1 = udpSimDatagram(100, "a", "b")
        val packet2 = udpSimDatagram(100, "a", "b")

        assertEquals(emptyList<DatagramPacket>(), qdisc.deliver(listOf(packet1, packet2)))
        assertEquals(null, qdisc.nextTaskDuration())
        assertEquals(10.milliseconds, qdisc.latencyQueue.emitter.nextTaskDuration())

        qdisc.latencyQueue.emitter.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packet1), qdisc.latencyQueue.emitter.emitPackets())
        assertEquals(null, qdisc.latencyQueue.emitter.nextTaskDuration())
    }

    @Test
    fun `idle bandwidth queue delivers first packet immediately when latency is zero`() {
        val qdisc = fifoUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = ZERO
        )
        val packet = udpSimDatagram(100, "a", "b")

        assertEquals(emptyList<DatagramPacket>(), qdisc.deliver(listOf(packet)))
        assertEquals(null, qdisc.nextTaskDuration())
        assertEquals(0.milliseconds, qdisc.latencyQueue.emitter.nextTaskDuration())
        assertEquals(listOf(packet), qdisc.latencyQueue.emitter.emitPackets())
    }

    @Test
    fun `zero-length packet is delivered after latency without shaping delay`() {
        val qdisc = fifoUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds
        )
        val packet = udpSimDatagram(0, "a", "b")

        assertEquals(emptyList<DatagramPacket>(), qdisc.deliver(listOf(packet)))
        assertEquals(null, qdisc.nextTaskDuration())
        assertEquals(10.milliseconds, qdisc.latencyQueue.emitter.nextTaskDuration())

        qdisc.latencyQueue.emitter.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packet), qdisc.latencyQueue.emitter.emitPackets())
        assertEquals(null, qdisc.latencyQueue.emitter.nextTaskDuration())
    }

    @Test
    fun `can apply latency before bandwidth queue`() {
        val qdisc = latencyThenBandwidthUdpSimQueue(
            bandwidth = Bandwidth(1_000),
            latency = 10.milliseconds
        )
        val packet = udpSimDatagram(100, "a", "b")

        qdisc.latencyQueue.receiver.receivePackets(listOf(packet))
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
        val packet1 = udpSimDatagram(100, "a", "b")

        qdisc.deliver(listOf(packet1))
        assertEquals(null, qdisc.nextTaskDuration())
        assertEquals(200.milliseconds, qdisc.latencyQueue.emitter.nextTaskDuration())

        qdisc.advanceAndExecuteAll(150.milliseconds)
        qdisc.latencyQueue.emitter.advanceAndExecuteAll(150.milliseconds)
        assertEquals(emptyList<DatagramPacket>(), qdisc.latencyQueue.emitter.emitPackets())
        assertEquals(50.milliseconds, qdisc.latencyQueue.emitter.nextTaskDuration())

        val packet2 = udpSimDatagram(100, "a", "b")

        qdisc.deliver(listOf(packet2))

        qdisc.latencyQueue.emitter.advanceAndExecuteAll(50.milliseconds)
        assertEquals(listOf(packet1), qdisc.latencyQueue.emitter.emitPackets())

        assertEquals(150.milliseconds, qdisc.latencyQueue.emitter.nextTaskDuration())
    }
}
