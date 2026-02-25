package io.libp2p.quicsim.network

import io.libp2p.quicsim.network.impl.BasicSimNetwork
import io.libp2p.quicsim.network.impl.BasicSimNetworkEngine
import io.libp2p.quicsim.network.impl.BasicSimNode
import io.libp2p.quicsim.network.impl.FifoSimQueueDiscipline
import io.libp2p.quicsim.network.impl.FqCodelSimQueueDiscipline
import io.libp2p.quicsim.network.impl.TransmissionMode
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

    @Test
    fun `packet from node1 to node2 through router reports expected delivery time with fq-codel`() {
        val node1 = BasicSimNode("node-1")
        val node2 = BasicSimNode("node-2")
        val node3 = BasicSimNode("node-3")
        val router = BasicSimNode("router-1")

        val bw = 1_000L
        val links = listOf(
            SimLink(node1, router, Duration.ofMillis(10), qdisc = FqCodelSimQueueDiscipline(bw)),
            SimLink(router, node1, Duration.ofMillis(10), qdisc = FqCodelSimQueueDiscipline(bw)),
            SimLink(node2, router, Duration.ofMillis(20), qdisc = FqCodelSimQueueDiscipline(bw)),
            SimLink(router, node2, Duration.ofMillis(20), qdisc = FqCodelSimQueueDiscipline(bw)),
            SimLink(node3, router, Duration.ofMillis(30), qdisc = FqCodelSimQueueDiscipline(bw)),
            SimLink(router, node3, Duration.ofMillis(30), qdisc = FqCodelSimQueueDiscipline(bw))
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

    @Test
    fun `packet from node1 to node2 with immediate shaping is latency-only`() {
        val node1 = BasicSimNode("node-1")
        val node2 = BasicSimNode("node-2")
        val node3 = BasicSimNode("node-3")
        val router = BasicSimNode("router-1")

        val bw = 1_000L
        val links = listOf(
            SimLink(
                node1,
                router,
                Duration.ofMillis(10),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                router,
                node1,
                Duration.ofMillis(10),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                node2,
                router,
                Duration.ofMillis(20),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                router,
                node2,
                Duration.ofMillis(20),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                node3,
                router,
                Duration.ofMillis(30),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                router,
                node3,
                Duration.ofMillis(30),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            )
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
        assertEquals(30L, engine.currentTimeMillis)
    }

    @Test
    fun `multiple packets are shaped at configured bandwidth in immediate mode`() {
        val node1 = BasicSimNode("node-1")
        val node2 = BasicSimNode("node-2")
        val node3 = BasicSimNode("node-3")
        val router = BasicSimNode("router-1")

        val bw = 1_000L
        val links = listOf(
            SimLink(
                node1,
                router,
                Duration.ofMillis(10),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                router,
                node1,
                Duration.ofMillis(10),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                node2,
                router,
                Duration.ofMillis(20),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                router,
                node2,
                Duration.ofMillis(20),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                node3,
                router,
                Duration.ofMillis(30),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            ),
            SimLink(
                router,
                node3,
                Duration.ofMillis(30),
                qdisc = FifoSimQueueDiscipline(bw, transmissionMode = TransmissionMode.SHAPED_IMMEDIATE)
            )
        )

        val network = BasicSimNetwork(nodes = listOf(node1, node2, node3, router), links = links)
        val engine = BasicSimNetworkEngine(network)

        val packets = listOf(
            SimPacket(id = 1, bytes = 1_000, srcNodeId = node1.id, dstNodeId = node2.id),
            SimPacket(id = 2, bytes = 1_000, srcNodeId = node1.id, dstNodeId = node2.id),
            SimPacket(id = 3, bytes = 1_000, srcNodeId = node1.id, dstNodeId = node2.id)
        )
        packets.forEach { engine.injectPacket(it) }

        val deliveredIds = mutableListOf<Long>()
        val deliveredTimes = mutableListOf<Long>()
        while (deliveredIds.size < packets.size) {
            val delivered = engine.advanceUntilDeliveryOr(10_000)
            assertTrue(delivered.isNotEmpty(), "Expected pending packets to eventually be delivered")
            deliveredIds += delivered.map { it.id }
            repeat(delivered.size) { deliveredTimes += engine.currentTimeMillis }
        }

        assertEquals(listOf(1L, 2L, 3L), deliveredIds)
        // hop latencies are 10ms + 20ms = 30ms, shaped at 1KB/s for 1KB packets
        assertEquals(listOf(30L, 1_030L, 2_030L), deliveredTimes)
    }
}
