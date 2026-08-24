package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.UnshapedUdpSimBandwidthQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnshapedUdpSimBandwidthQueueTest {
    @Test
    fun `emits received packets without bandwidth delay`() {
        val queue = UnshapedUdpSimBandwidthQueue()
        val packet1 = udpSimDatagram(100, "a", "b")
        val packet2 = udpSimDatagram(200, "c", "d")

        queue.receivePackets(listOf(packet1, packet2))

        assertEquals(listOf(packet1, packet2), queue.emitPackets())
        assertEquals(emptyList<Any>(), queue.emitPackets())
    }
}
