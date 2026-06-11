package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.UdpSimLatencyQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
    fun `latency ahead enqueue processor can write packets before normal processor time advances`() {
        val latencyDelay = UdpSimLatencyQueue(100.milliseconds)
        val aheadEnqueue = latencyDelay.aheadEnqueueProcessor
        val packet = UdpSimPacket(1, 100, "a", "b")

        aheadEnqueue.advanceAndExecuteAll(60.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), aheadEnqueue.deliver(listOf(packet)))

        assertEquals(160.milliseconds, latencyDelay.nextTaskDuration())
        latencyDelay.advanceAndExecuteAll(159.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), latencyDelay.deliver(emptyList()))

        latencyDelay.advanceAndExecuteAll(1.milliseconds)
        assertEquals(listOf(packet), latencyDelay.deliver(emptyList()))
        assertEquals(null, latencyDelay.nextTaskDuration())
    }

    @Test
    fun `latency queue can move read and write ends forward by latency`() {
        val latencyDelay = UdpSimLatencyQueue(100.milliseconds)
        val aheadRead = latencyDelay.aheadProcessor
        val aheadWrite = latencyDelay.aheadEnqueueProcessor
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), latencyDelay.deliver(listOf(packet1)))

        aheadRead.advanceAndExecuteAll(100.milliseconds)
        aheadWrite.advanceAndExecuteAll(100.milliseconds)

        assertEquals(listOf(packet1), aheadRead.deliver(emptyList()))
        assertEquals(emptyList<UdpSimPacket>(), aheadWrite.deliver(listOf(packet2)))

        assertEquals(200.milliseconds, latencyDelay.nextTaskDuration())
        latencyDelay.advanceAndExecuteAll(200.milliseconds)
        assertEquals(listOf(packet2), latencyDelay.deliver(emptyList()))
    }

    @Test
    fun `latency queue supports parallel read and write end advancement`() {
        val latencyDelay = UdpSimLatencyQueue(100.milliseconds)
        val aheadRead = latencyDelay.aheadProcessor
        val aheadWrite = latencyDelay.aheadEnqueueProcessor
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val readResult = AtomicReference<List<UdpSimPacket>>()
        val error = AtomicReference<Throwable>()

        assertEquals(emptyList<UdpSimPacket>(), latencyDelay.deliver(listOf(packet1)))

        val readThread = thread {
            runCatching {
                ready.countDown()
                start.await()
                aheadRead.advanceAndExecuteAll(100.milliseconds)
                readResult.set(aheadRead.deliver(emptyList()))
            }.onFailure { error.compareAndSet(null, it) }
        }
        val writeThread = thread {
            runCatching {
                ready.countDown()
                start.await()
                aheadWrite.advanceAndExecuteAll(100.milliseconds)
                aheadWrite.deliver(listOf(packet2))
            }.onFailure { error.compareAndSet(null, it) }
        }

        ready.await()
        start.countDown()
        readThread.join()
        writeThread.join()
        error.get()?.let { throw it }

        assertEquals(listOf(packet1), readResult.get())
        assertEquals(200.milliseconds, latencyDelay.nextTaskDuration())
        latencyDelay.advanceAndExecuteAll(200.milliseconds)
        assertEquals(listOf(packet2), latencyDelay.deliver(emptyList()))
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
