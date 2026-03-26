package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.UdpSimLinks
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimQueueDiscipline as FifoSimQueueDiscipline2_2

class BasicUdpSimNetworkEngineTest {

    @Test
    fun `routes through intermediate node`() {
        val nodeA = UdpSimNode("a")
        val nodeR = UdpSimNode("r")
        val nodeB = UdpSimNode("b")
        val engine = UdpSimNetworkEngineImpl(
            BasicUdpSimNetwork(
                nodes = listOf(nodeA, nodeR, nodeB),
                links =
                    UdpSimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .links
            ))

        val packet = UdpSimPacket(1, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(listOf(packet)))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(emptyList()))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(listOf(packet), engine.deliver(emptyList()))
    }

    @Test
    fun `throws for packet to the same endpoint in star topology`() {
        val nodeA = UdpSimNode("a")
        val nodeR = UdpSimNode("r")
        val nodeB = UdpSimNode("b")
        val engine = UdpSimNetworkEngineImpl(
            BasicUdpSimNetwork(
                nodes = listOf(nodeA, nodeR, nodeB),
                links =
                    UdpSimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .links
            )
        )
        val packet = UdpSimPacket(1, 100, "a", "a")

        assertThrows(IllegalStateException::class.java) {
            engine.deliver(listOf(packet))
        }
    }

    @Test
    fun `preserves packet order on the same star path`() {
        val nodeA = UdpSimNode("a")
        val nodeR = UdpSimNode("r")
        val nodeB = UdpSimNode("b")
        val engine = UdpSimNetworkEngineImpl(
            BasicUdpSimNetwork(
                nodes = listOf(nodeA, nodeR, nodeB),
                links =
                    UdpSimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .links
            )
        )
        val packet1 = UdpSimPacket(1, 100, "a", "b")
        val packet2 = UdpSimPacket(2, 100, "a", "b")

        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(listOf(packet1, packet2)))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        val delivered = mutableListOf<UdpSimPacket>()
        repeat(4) {
            engine.advanceAndExecuteAll(engine.nextTaskDuration()!!)
            delivered += engine.deliver(emptyList())
        }

        assertEquals(listOf(packet1, packet2), delivered)
    }

    @Test
    fun `handles simultaneous reverse traffic through the router`() {
        val nodeA = UdpSimNode("a")
        val nodeR = UdpSimNode("r")
        val nodeB = UdpSimNode("b")
        val engine = UdpSimNetworkEngineImpl(
            BasicUdpSimNetwork(
                nodes = listOf(nodeA, nodeR, nodeB),
                links =
                    UdpSimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .links
            )
        )
        val packetAb = UdpSimPacket(1, 100, "a", "b")
        val packetBa = UdpSimPacket(2, 100, "b", "a")

        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(listOf(packetAb, packetBa)))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(emptyList()))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(setOf(packetAb, packetBa), engine.deliver(emptyList()).toSet())
        assertEquals(null, engine.nextTaskDuration())
    }

    @Test
    fun `fans out packets from one endpoint to different destinations via the same first hop`() {
        val nodeA = UdpSimNode("a")
        val nodeR = UdpSimNode("r")
        val nodeB = UdpSimNode("b")
        val nodeC = UdpSimNode("c")
        val engine = UdpSimNetworkEngineImpl(
            BasicUdpSimNetwork(
                nodes = listOf(nodeA, nodeR, nodeB, nodeC),
                links =
                    UdpSimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .addBiDir(nodeR, nodeC)
                        .links
            )
        )
        val packetAb = UdpSimPacket(1, 100, "a", "b")
        val packetAc = UdpSimPacket(2, 100, "a", "c")

        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(listOf(packetAb, packetAc)))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(emptyList()))
        assertEquals(100.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(100.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(emptyList()))
        assertEquals(10.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packetAb), engine.deliver(emptyList()))
        assertEquals(100.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packetAc), engine.deliver(emptyList()))
        assertEquals(null, engine.nextTaskDuration())
    }
}
