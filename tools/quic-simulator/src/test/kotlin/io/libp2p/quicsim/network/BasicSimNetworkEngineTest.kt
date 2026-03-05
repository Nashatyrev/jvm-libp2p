package io.libp2p.quicsim.network

import io.libp2p.quicsim.network.impl.BasicSimNetworkEngine
import io.libp2p.quicsim.network.impl.FifoSimQueueDiscipline
import io.libp2p.quicsim.network.impl.FqCodelSimQueueDiscipline
import io.libp2p.quicsim.network.impl.TransmissionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class BasicSimNetworkEngineTest {

    @Test
    fun `packet from node1 to node2 through router reports expected delivery time`() {
        val bw = 1_000L
        val fixture = buildThreeNodeRouterFixture(
            qdiscFactory = { FifoSimQueueDiscipline(bw) }
        )

        val packet = SimPacket(
            id = 1,
            bytes = 1_000,
            srcNodeId = fixture.node1.id,
            dstNodeId = fixture.node2.id
        )

        val delivered = fixture.engine.deliver(listOf(packet))

        assertEquals(1, delivered.size)
        assertEquals(packet.id, delivered.first().id)
        assertEquals(fixture.node2.id, delivered.first().dstNodeId)

        // hop1: 1000ms tx + 10ms latency = 1010ms
        // hop2: 1000ms tx + 20ms latency = 1020ms
        // total = 2030ms
        assertEquals(2_030L, fixture.engine.currentTimeMillis)

        fixture.engine.advanceAndExecuteAll((10_000L - fixture.engine.currentTimeMillis).milliseconds)
        val noMore = fixture.engine.deliver(emptyList())
        assertTrue(noMore.isEmpty())
        assertEquals(10_000L, fixture.engine.currentTimeMillis)
    }

    @Test
    fun `packet from node1 to node2 through router reports expected delivery time with fq-codel`() {
        val bw = 1_000L
        val fixture = buildThreeNodeRouterFixture(
            qdiscFactory = { FqCodelSimQueueDiscipline(bw) }
        )

        val packet = SimPacket(
            id = 1,
            bytes = 1_000,
            srcNodeId = fixture.node1.id,
            dstNodeId = fixture.node2.id
        )

        val delivered = fixture.engine.deliver(listOf(packet))

        assertEquals(1, delivered.size)
        assertEquals(packet.id, delivered.first().id)
        assertEquals(fixture.node2.id, delivered.first().dstNodeId)

        // hop1: 1000ms tx + 10ms latency = 1010ms
        // hop2: 1000ms tx + 20ms latency = 1020ms
        // total = 2030ms
        assertEquals(2_030L, fixture.engine.currentTimeMillis)

        fixture.engine.advanceAndExecuteAll((10_000L - fixture.engine.currentTimeMillis).milliseconds)
        val noMore = fixture.engine.deliver(emptyList())
        assertTrue(noMore.isEmpty())
        assertEquals(10_000L, fixture.engine.currentTimeMillis)
    }

    @Test
    fun `packet from node1 to node2 with immediate shaping is latency-only`() {
        val bw = 1_000L
        val fixture = buildThreeNodeRouterFixture(
            qdiscFactory = {
                FifoSimQueueDiscipline(
                    bw,
                    transmissionMode = TransmissionMode.SHAPED_IMMEDIATE
                )
            }
        )

        val packet = SimPacket(
            id = 1,
            bytes = 1_000,
            srcNodeId = fixture.node1.id,
            dstNodeId = fixture.node2.id
        )

        val delivered = fixture.engine.deliver(listOf(packet))

        assertEquals(1, delivered.size)
        assertEquals(packet.id, delivered.first().id)
        assertEquals(30L, fixture.engine.currentTimeMillis)
    }

    @Test
    fun `multiple packets are shaped at configured bandwidth in immediate mode`() {
        val bw = 1_000L
        val fixture = buildThreeNodeRouterFixture(
            qdiscFactory = {
                FifoSimQueueDiscipline(
                    bw,
                    transmissionMode = TransmissionMode.SHAPED_IMMEDIATE
                )
            }
        )

        val packets = listOf(
            SimPacket(id = 1, bytes = 1_000, srcNodeId = fixture.node1.id, dstNodeId = fixture.node2.id),
            SimPacket(id = 2, bytes = 1_000, srcNodeId = fixture.node1.id, dstNodeId = fixture.node2.id),
            SimPacket(id = 3, bytes = 1_000, srcNodeId = fixture.node1.id, dstNodeId = fixture.node2.id)
        )

        val deliveredIds = mutableListOf<Long>()
        val deliveredTimes = mutableListOf<Long>()

        var delivered = fixture.engine.deliver(packets)
        while (deliveredIds.size < packets.size) {
            assertTrue(delivered.isNotEmpty(), "Expected pending packets to eventually be delivered")
            deliveredIds += delivered.map { it.id }
            repeat(delivered.size) { deliveredTimes += fixture.engine.currentTimeMillis }
            delivered = fixture.engine.deliver(emptyList())
        }

        assertEquals(listOf(1L, 2L, 3L), deliveredIds)
        // hop latencies are 10ms + 20ms = 30ms, shaped at 1KB/s for 1KB packets
        assertEquals(listOf(30L, 1_030L, 2_030L), deliveredTimes)
    }

    @Test
    fun `shared egress link from multiple sources serializes packets`() {
        val bw = 1_000L
        val fixture = buildThreeNodeRouterFixture(
            qdiscFactory = { FifoSimQueueDiscipline(bw) }
        )

        val packetFromNode1 = SimPacket(
            id = 1,
            bytes = 1_000,
            srcNodeId = fixture.node1.id,
            dstNodeId = fixture.node2.id
        )
        val packetFromNode3 = SimPacket(
            id = 2,
            bytes = 1_000,
            srcNodeId = fixture.node3.id,
            dstNodeId = fixture.node2.id
        )

        val firstDelivery = fixture.engine.deliver(listOf(packetFromNode1, packetFromNode3))
        assertEquals(listOf(packetFromNode1.id), firstDelivery.map { it.id })
        assertEquals(2_030L, fixture.engine.currentTimeMillis)

        val secondDelivery = fixture.engine.deliver(emptyList())
        assertEquals(listOf(packetFromNode3.id), secondDelivery.map { it.id })
        assertEquals(3_030L, fixture.engine.currentTimeMillis)
    }

    @Test
    fun `shared first hop for different destinations delays only while link is shared`() {
        val bw = 1_000L
        val fixture = buildThreeNodeRouterFixture(
            qdiscFactory = { FifoSimQueueDiscipline(bw) }
        )

        val toNode2 = SimPacket(
            id = 1,
            bytes = 1_000,
            srcNodeId = fixture.node1.id,
            dstNodeId = fixture.node2.id
        )
        val toNode3 = SimPacket(
            id = 2,
            bytes = 1_000,
            srcNodeId = fixture.node1.id,
            dstNodeId = fixture.node3.id
        )

        val firstDelivery = fixture.engine.deliver(listOf(toNode2, toNode3))
        assertEquals(listOf(toNode2.id), firstDelivery.map { it.id })
        assertEquals(2_030L, fixture.engine.currentTimeMillis)

        val secondDelivery = fixture.engine.deliver(emptyList())
        assertEquals(listOf(toNode3.id), secondDelivery.map { it.id })
        assertEquals(3_040L, fixture.engine.currentTimeMillis)
    }

    @Test
    fun `advanceAndExecuteAll rejects negative duration`() {
        val fixture = buildThreeNodeRouterFixture(
            qdiscFactory = { FifoSimQueueDiscipline(1_000L) }
        )

        val packet = SimPacket(
            id = 1,
            bytes = 1_000,
            srcNodeId = fixture.node1.id,
            dstNodeId = fixture.node2.id
        )
        fixture.engine.deliver(listOf(packet))
        fixture.engine.advanceAndExecuteAll(1_000.milliseconds)

        assertThrows(IllegalArgumentException::class.java) {
            fixture.engine.advanceAndExecuteAll((-1).milliseconds)
        }
    }

    @Test
    fun `packet with no route is dropped and engine has no deliveries`() {
        val builder = TestNetworkBuilder()
        val node1 = builder.node("node-1")
        val isolatedNode = builder.node("node-2")
        val network = builder.build()
        val engine = BasicSimNetworkEngine(network)

        val delivered = engine.deliver(
            listOf(
                SimPacket(
                    id = 1,
                    bytes = 1_000,
                    srcNodeId = node1.id,
                    dstNodeId = isolatedNode.id
                )
            )
        )

        assertTrue(delivered.isEmpty())
        assertEquals(0L, engine.currentTimeMillis)

        engine.advanceAndExecuteAll(5_000.milliseconds)
        assertEquals(5_000L, engine.currentTimeMillis)
    }

    @Test
    fun `packet is delivered across multi-router path with more than two hops`() {
        val bw = 1_000L
        val hopLatency = Duration.ofMillis(5)
        val builder = TestNetworkBuilder()
        val node1 = builder.node("node-1")
        val router1 = builder.node("router-1")
        val router2 = builder.node("router-2")
        val node2 = builder.node("node-2")

        builder
            .bidirectional(node1, router1, hopLatency, qdiscFactory = { FifoSimQueueDiscipline(bw) })
            .bidirectional(router1, router2, hopLatency, qdiscFactory = { FifoSimQueueDiscipline(bw) })
            .bidirectional(router2, node2, hopLatency, qdiscFactory = { FifoSimQueueDiscipline(bw) })

        val engine = BasicSimNetworkEngine(builder.build())
        val packet = SimPacket(
            id = 1,
            bytes = 1_000,
            srcNodeId = node1.id,
            dstNodeId = node2.id
        )

        val delivered = engine.deliver(listOf(packet))

        assertEquals(listOf(packet.id), delivered.map { it.id })
        assertEquals(node2.id, delivered.first().dstNodeId)
        // 3 hops: (1000ms tx + 5ms latency) * 3 = 3015ms
        assertEquals(3_015L, engine.currentTimeMillis)
    }
}
