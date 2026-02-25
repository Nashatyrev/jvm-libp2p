package io.libp2p.quicsim.network

import io.libp2p.quicsim.network.impl.BasicSimNetwork
import io.libp2p.quicsim.network.impl.BasicSimNetworkEngine
import io.libp2p.quicsim.network.impl.BasicSimNode
import io.libp2p.quicsim.network.impl.FifoSimQueueDiscipline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class BasicSimNetworkEngineTest {

    @Test
    fun `packet from node1 to node2 through router reports expected delivery time`() {
        val node1 = BasicSimNode("node-1")
        val node2 = BasicSimNode("node-2")
        val node3 = BasicSimNode("node-3")
        val router = BasicSimNode("router-1")

        val bw = 1_000L
        val links = listOf(
            SimLink(node1, router, Duration.ofMillis(10), qdisc = FifoSimQueueDiscipline(bw)),
            SimLink(router, node1, Duration.ofMillis(10), qdisc = FifoSimQueueDiscipline(bw)),
            SimLink(node2, router, Duration.ofMillis(20), qdisc = FifoSimQueueDiscipline(bw)),
            SimLink(router, node2, Duration.ofMillis(20), qdisc = FifoSimQueueDiscipline(bw)),
            SimLink(node3, router, Duration.ofMillis(30), qdisc = FifoSimQueueDiscipline(bw)),
            SimLink(router, node3, Duration.ofMillis(30), qdisc = FifoSimQueueDiscipline(bw))
        )

        val network = BasicSimNetwork(nodes = listOf(node1, node2, node3, router), links = links)
        val engine = BasicSimNetworkEngine(network)

        val packet = SimPacket(
            id = 1,
            bytes = 1_000,
            srcNodeId = node1.id,
            dstNodeId = node2.id
        )

        engine.injectPacket(packet)
        val delivered = engine.advanceUntilDeliveryOr(10_000)

        assertEquals(1, delivered.size)
        assertEquals(packet.id, delivered.first().id)
        assertEquals(node2.id, delivered.first().dstNodeId)

        // hop1: 1000ms tx + 10ms latency = 1010ms
        // hop2: 1000ms tx + 20ms latency = 1020ms
        // total = 2030ms
        assertEquals(2_030L, engine.currentTimeMillis)

        val noMore = engine.advanceUntilDeliveryOr(10_000)
        assertTrue(noMore.isEmpty())
        assertEquals(10_000L, engine.currentTimeMillis)
    }
}
