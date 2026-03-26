package io.libp2p.quicsim.network2

import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network.SimPacket
import io.libp2p.quicsim.network2.impl.BasicSimNetwork2
import io.libp2p.quicsim.network2.impl.SimLinks
import io.libp2p.quicsim.network2.impl.SimNetworkEngine2Impl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import io.libp2p.quicsim.network2.impl.FifoSimQueueDiscipline2 as FifoSimQueueDiscipline2_2

class BasicSimNetworkEngine2Test {

    @Test
    fun `routes through intermediate node`() {
        val nodeA = SimNode("a")
        val nodeR = SimNode("r")
        val nodeB = SimNode("b")
        val engine = SimNetworkEngine2Impl(
            BasicSimNetwork2(
                nodes = listOf(nodeA, nodeR, nodeB),
                links =
                    SimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .links
            ))

        val packet = SimPacket(1, 100, "a", "b")

        assertEquals(emptyList<SimPacket>(), engine.deliver(listOf(packet)))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(emptyList<SimPacket>(), engine.deliver(emptyList()))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(listOf(packet), engine.deliver(emptyList()))
    }

    @Test
    fun `throws for packet to the same endpoint in star topology`() {
        val nodeA = SimNode("a")
        val nodeR = SimNode("r")
        val nodeB = SimNode("b")
        val engine = SimNetworkEngine2Impl(
            BasicSimNetwork2(
                nodes = listOf(nodeA, nodeR, nodeB),
                links =
                    SimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .links
            )
        )
        val packet = SimPacket(1, 100, "a", "a")

        assertThrows(IllegalStateException::class.java) {
            engine.deliver(listOf(packet))
        }
    }

    @Test
    fun `preserves packet order on the same star path`() {
        val nodeA = SimNode("a")
        val nodeR = SimNode("r")
        val nodeB = SimNode("b")
        val engine = SimNetworkEngine2Impl(
            BasicSimNetwork2(
                nodes = listOf(nodeA, nodeR, nodeB),
                links =
                    SimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .links
            )
        )
        val packet1 = SimPacket(1, 100, "a", "b")
        val packet2 = SimPacket(2, 100, "a", "b")

        assertEquals(emptyList<SimPacket>(), engine.deliver(listOf(packet1, packet2)))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        val delivered = mutableListOf<SimPacket>()
        repeat(4) {
            engine.advanceAndExecuteAll(engine.nextTaskDuration()!!)
            delivered += engine.deliver(emptyList())
        }

        assertEquals(listOf(packet1, packet2), delivered)
    }

    @Test
    fun `handles simultaneous reverse traffic through the router`() {
        val nodeA = SimNode("a")
        val nodeR = SimNode("r")
        val nodeB = SimNode("b")
        val engine = SimNetworkEngine2Impl(
            BasicSimNetwork2(
                nodes = listOf(nodeA, nodeR, nodeB),
                links =
                    SimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .links
            )
        )
        val packetAb = SimPacket(1, 100, "a", "b")
        val packetBa = SimPacket(2, 100, "b", "a")

        assertEquals(emptyList<SimPacket>(), engine.deliver(listOf(packetAb, packetBa)))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(emptyList<SimPacket>(), engine.deliver(emptyList()))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(setOf(packetAb, packetBa), engine.deliver(emptyList()).toSet())
        assertEquals(null, engine.nextTaskDuration())
    }

    @Test
    fun `fans out packets from one endpoint to different destinations via the same first hop`() {
        val nodeA = SimNode("a")
        val nodeR = SimNode("r")
        val nodeB = SimNode("b")
        val nodeC = SimNode("c")
        val engine = SimNetworkEngine2Impl(
            BasicSimNetwork2(
                nodes = listOf(nodeA, nodeR, nodeB, nodeC),
                links =
                    SimLinks()
                        .withQDisc { FifoSimQueueDiscipline2_2(Bandwidth(1_000), 10.milliseconds) }
                        .addBiDir(nodeA, nodeR)
                        .addBiDir(nodeR, nodeB)
                        .addBiDir(nodeR, nodeC)
                        .links
            )
        )
        val packetAb = SimPacket(1, 100, "a", "b")
        val packetAc = SimPacket(2, 100, "a", "c")

        assertEquals(emptyList<SimPacket>(), engine.deliver(listOf(packetAb, packetAc)))
        assertEquals(110.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(110.milliseconds)
        assertEquals(emptyList<SimPacket>(), engine.deliver(emptyList()))
        assertEquals(100.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(100.milliseconds)
        assertEquals(emptyList<SimPacket>(), engine.deliver(emptyList()))
        assertEquals(10.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(10.milliseconds)
        assertEquals(listOf(packetAb), engine.deliver(emptyList()))
        assertEquals(100.milliseconds, engine.nextTaskDuration())

        engine.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packetAc), engine.deliver(emptyList()))
        assertEquals(null, engine.nextTaskDuration())
    }
}
