package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.deliver
import io.libp2p.quicsim.udpnetwork.impl.FqCodelUdpSimBandwidthQueue
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FqCodelUdpSimBandwidthQueueTest {
    @Test
    fun `spaces packets according to bandwidth`() {
        val bandwidthQueue = FqCodelUdpSimBandwidthQueue(Bandwidth(1_000))
        val packet1 = udpSimDatagram(100, "a", "b")
        val packet2 = udpSimDatagram(100, "a", "b")

        assertEquals(listOf(packet1), bandwidthQueue.deliver(listOf(packet1, packet2)))
        assertEquals(100.milliseconds, bandwidthQueue.nextTaskDuration())

        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packet2), bandwidthQueue.deliver(emptyList()))
        assertEquals(null, bandwidthQueue.nextTaskDuration())
    }

    @Test
    fun `interleaves active flows a quantum at a time`() {
        val bandwidthQueue = FqCodelUdpSimBandwidthQueue(
            bandwidth = Bandwidth(1_000),
            targetDelay = 1_000.milliseconds,
            quantumBytes = 100
        )
        val flowA1 = udpSimDatagram(100, "a", "z")
        val flowA2 = udpSimDatagram(100, "a", "z")
        val flowB1 = udpSimDatagram(100, "b", "z")
        val flowB2 = udpSimDatagram(100, "b", "z")

        assertEquals(listOf(flowA1), bandwidthQueue.deliver(listOf(flowA1, flowA2, flowB1, flowB2)))
        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(flowB1), bandwidthQueue.deliver(emptyList()))
        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(flowA2), bandwidthQueue.deliver(emptyList()))
        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(flowB2), bandwidthQueue.deliver(emptyList()))
    }

    @Test
    fun `serves a newly active sparse flow ahead of a backlogged one`() {
        val bandwidthQueue = FqCodelUdpSimBandwidthQueue(Bandwidth(1_000), targetDelay = 10.seconds)
        val bulk = List(20) { udpSimDatagram(100, "a", "bulk") }
        val sent = bandwidthQueue.deliver(bulk).toMutableList()
        // Let the bulk flow spend its first quantum and move to the old list.
        repeat(16) {
            bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
            sent += bandwidthQueue.deliver(emptyList())
        }
        val vote = udpSimDatagram(100, "a", "vote")
        bandwidthQueue.receivePackets(listOf(vote))
        // One bulk packet is already scheduled; the vote goes right after it.
        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        sent += bandwidthQueue.deliver(emptyList())
        bandwidthQueue.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(vote), bandwidthQueue.deliver(emptyList()))
    }

    @Test
    fun `has no age cap by default`() {
        val bandwidthQueue = FqCodelUdpSimBandwidthQueue(Bandwidth(1_000), targetDelay = 10.seconds)
        val packets = List(10) { udpSimDatagram(100, "a", "b") }
        assertEquals(packets, drain(bandwidthQueue, packets))
    }

    @Test
    fun `drops more often the longer a flow stays overloaded`() {
        val bandwidthQueue = FqCodelUdpSimBandwidthQueue(Bandwidth(10_000))
        // 2x the link rate for 3 s: 100-byte packets every 5 ms against 10 ms to send each.
        val delivered = mutableListOf<DatagramPacket>()
        var now = Duration.ZERO
        val sent = (0 until 600).map { i ->
            val at = (i * 5).milliseconds
            bandwidthQueue.advanceAndExecuteAll(at - now)
            now = at
            udpSimDatagram(100, "a", "b").also { delivered += bandwidthQueue.deliver(listOf(it)) }
        }
        delivered += drain(bandwidthQueue, emptyList())
        val dropped = sent.filter { it !in delivered }
        // CoDel's control law, unlike one drop per interval, keeps up with an open-loop 2x overload
        // well beyond 3 s / 100 ms = 30 drops.
        assertTrue(dropped.size > 60) { "dropped ${dropped.size}" }
        assertTrue(dropped.all { it.refCnt() == 0 })
    }

    private fun drain(queue: FqCodelUdpSimBandwidthQueue, packets: List<DatagramPacket>): List<DatagramPacket> {
        val out = queue.deliver(packets).toMutableList()
        while (true) {
            val next = queue.nextTaskDuration() ?: return out
            queue.advanceAndExecuteAll(next)
            out += queue.deliver(emptyList())
        }
    }
}
