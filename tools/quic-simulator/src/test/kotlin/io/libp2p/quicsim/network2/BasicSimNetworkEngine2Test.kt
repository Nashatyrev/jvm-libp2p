package io.libp2p.quicsim.network2

import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network.SimPacket
import io.libp2p.quicsim.network2.impl.BasicSimNetwork2
import io.libp2p.quicsim.network2.impl.SimLinks
import io.libp2p.quicsim.network2.impl.SimNetworkEngine2Impl
import org.junit.jupiter.api.Assertions.assertEquals
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
}
