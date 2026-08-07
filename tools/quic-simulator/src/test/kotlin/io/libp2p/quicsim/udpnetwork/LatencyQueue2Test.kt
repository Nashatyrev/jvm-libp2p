package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.LatencyQueue
import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class LatencyQueue2Test {

    @Test
    fun `emitter emits packets after latency`() {
        val latencyQueue: LatencyQueue<String> = LatencyQueueImpl(10.milliseconds)

        assertEquals(10.milliseconds, latencyQueue.minimalLatency)
        latencyQueue.receiver.receivePackets(listOf("packet-1"))

        assertEquals(10.milliseconds, latencyQueue.emitter.nextTaskDuration())
        assertEquals(emptyList<String>(), latencyQueue.emitter.emitPackets())

        latencyQueue.emitter.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf("packet-1"), latencyQueue.emitter.emitPackets())
        assertEquals(null, latencyQueue.emitter.nextTaskDuration())
    }

    @Test
    fun `emitter notifies when packets are enqueued`() {
        val latencyQueue = LatencyQueueImpl<String>(10.milliseconds)
        var notificationCount = 0

        latencyQueue.emitter.addPacketAddedListener {
            notificationCount++
        }

        latencyQueue.receiver.receivePackets(emptyList())
        assertEquals(0, notificationCount)

        latencyQueue.receiver.receivePackets(listOf("packet-1", "packet-2"))
        assertEquals(1, notificationCount)

        latencyQueue.receivePacketsAt(listOf("packet-3"), 5.milliseconds)
        assertEquals(2, notificationCount)

        latencyQueue.receiveTimedPackets(
            packets = listOf(6.milliseconds to "packet-4"),
            timeExtractor = { it.first },
            packetExtractor = { it.second }
        )
        assertEquals(3, notificationCount)
    }

    @Test
    fun `receiver can enqueue packets ahead of emitter`() {
        val latencyQueue = LatencyQueueImpl<String>(100.milliseconds)

        latencyQueue.receiver.advanceAndExecuteAll(50.milliseconds)
        latencyQueue.receiver.receivePackets(listOf("packet-1"))

        assertEquals(150.milliseconds, latencyQueue.emitter.nextTaskDuration())
        latencyQueue.emitter.advanceAndExecuteAll(149.milliseconds)
        assertEquals(emptyList<String>(), latencyQueue.emitter.emitPackets())

        latencyQueue.emitter.advanceAndExecuteAll(1.milliseconds)
        assertEquals(listOf("packet-1"), latencyQueue.emitter.emitPackets())
    }

    @Test
    fun `emitter can catch packets received behind its current time`() {
        val latencyQueue = LatencyQueueImpl<String>(100.milliseconds)

        latencyQueue.emitter.advanceAndExecuteAll(100.milliseconds)
        latencyQueue.receiver.receivePackets(listOf("packet-1"))

        assertEquals(0.milliseconds, latencyQueue.emitter.nextTaskDuration())
        assertEquals(listOf("packet-1"), latencyQueue.emitter.emitPackets())
    }

    @Test
    fun `emitter can advance past receiver while idle and catch late packets`() {
        val latencyQueue = LatencyQueueImpl<String>(100.milliseconds)

        latencyQueue.emitter.advanceAndExecuteAll(101.milliseconds)
        latencyQueue.receiver.receivePackets(listOf("packet-1"))

        assertEquals(0.milliseconds, latencyQueue.emitter.nextTaskDuration())
        assertEquals(listOf("packet-1"), latencyQueue.emitter.emitPackets())

        latencyQueue.receiver.advanceAndExecuteAll(50.milliseconds)
        latencyQueue.receiver.receivePackets(listOf("packet-2"))

        assertEquals(49.milliseconds, latencyQueue.emitter.nextTaskDuration())
        latencyQueue.emitter.advanceAndExecuteAll(49.milliseconds)
        assertEquals(listOf("packet-2"), latencyQueue.emitter.emitPackets())
    }

    @Test
    fun `latency must be non-negative`() {
        assertThrows(IllegalArgumentException::class.java) {
            LatencyQueueImpl<String>((-1).milliseconds)
        }
    }

    @Test
    fun `receiver and emitter can be advanced in parallel`() {
        val latencyQueue = LatencyQueueImpl<String>(100.milliseconds)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val emitted = AtomicReference<List<String>>()
        val error = AtomicReference<Throwable>()

        latencyQueue.receiver.receivePackets(listOf("packet-1"))

        val emitThread = thread {
            runCatching {
                ready.countDown()
                start.await()
                latencyQueue.emitter.advanceAndExecuteAll(100.milliseconds)
                emitted.set(latencyQueue.emitter.emitPackets())
            }.onFailure { error.compareAndSet(null, it) }
        }
        val receiveThread = thread {
            runCatching {
                ready.countDown()
                start.await()
                latencyQueue.receiver.advanceAndExecuteAll(100.milliseconds)
                latencyQueue.receiver.receivePackets(listOf("packet-2"))
            }.onFailure { error.compareAndSet(null, it) }
        }

        ready.await()
        start.countDown()
        emitThread.join()
        receiveThread.join()
        error.get()?.let { throw it }

        assertEquals(listOf("packet-1"), emitted.get())
        assertEquals(100.milliseconds, latencyQueue.emitter.nextTaskDuration())

        latencyQueue.emitter.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf("packet-2"), latencyQueue.emitter.emitPackets())
    }

    @Test
    fun `concurrent receivers enqueue every packet without loss`() {
        val latencyQueue = LatencyQueueImpl<String>(0.milliseconds)
        val threadCount = 8
        val packetsPerThread = 250
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val expectedPackets = (0 until threadCount).flatMap { threadIndex ->
            (0 until packetsPerThread).map { packetIndex ->
                "$threadIndex-$packetIndex"
            }
        }.toSet()

        val threads = (0 until threadCount).map { threadIndex ->
            thread {
                runCatching {
                    ready.countDown()
                    start.await()
                    latencyQueue.receiver.receivePackets(
                        (0 until packetsPerThread).map { packetIndex ->
                            "$threadIndex-$packetIndex"
                        }
                    )
                }.onFailure { error.compareAndSet(null, it) }
            }
        }

        ready.await()
        start.countDown()
        threads.forEach { it.join() }
        error.get()?.let { throw it }

        val emitted = latencyQueue.emitter.emitPackets()
        assertEquals(threadCount * packetsPerThread, emitted.size)
        assertEquals(expectedPackets, emitted.toSet())
        assertEquals(null, latencyQueue.emitter.nextTaskDuration())
    }

    @Test
    fun `receiver and emitter can concurrently enqueue advance and emit`() {
        val latencyQueue = LatencyQueueImpl<Int>(1.milliseconds)
        val packetCount = 500
        val emitted = ConcurrentLinkedQueue<Int>()
        val producerDone = AtomicBoolean(false)
        val error = AtomicReference<Throwable>()

        val producer = thread {
            runCatching {
                repeat(packetCount) { packet ->
                    latencyQueue.receiver.receivePackets(listOf(packet))
                    latencyQueue.receiver.advanceAndExecuteAll(1.milliseconds)
                }
                producerDone.set(true)
            }.onFailure { error.compareAndSet(null, it) }
        }
        val consumer = thread {
            runCatching {
                while (!producerDone.get() || emitted.size < packetCount) {
                    val nextTaskDuration = latencyQueue.emitter.nextTaskDuration()
                    if (nextTaskDuration == null) {
                        Thread.yield()
                    } else {
                        latencyQueue.emitter.advanceAndExecuteAll(nextTaskDuration)
                        emitted += latencyQueue.emitter.emitPackets()
                    }
                }
            }.onFailure { error.compareAndSet(null, it) }
        }

        producer.join()
        consumer.join()
        error.get()?.let { throw it }

        assertEquals((0 until packetCount).toList(), emitted.toList())
        assertEquals(null, latencyQueue.emitter.nextTaskDuration())
    }
}
