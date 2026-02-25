package io.libp2p.quicsim.network

import io.libp2p.quicsim.network.impl.FifoSimQueueDiscipline
import io.libp2p.quicsim.network.impl.FqCodelSimQueueDiscipline
import io.libp2p.quicsim.network.impl.TransmissionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

        fixture.engine.injectPacket(packet)
        val delivered = fixture.engine.advanceUntilDeliveryOr(10_000)

        assertEquals(1, delivered.size)
        assertEquals(packet.id, delivered.first().id)
        assertEquals(fixture.node2.id, delivered.first().dstNodeId)

        // hop1: 1000ms tx + 10ms latency = 1010ms
        // hop2: 1000ms tx + 20ms latency = 1020ms
        // total = 2030ms
        assertEquals(2_030L, fixture.engine.currentTimeMillis)

        val noMore = fixture.engine.advanceUntilDeliveryOr(10_000)
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

        fixture.engine.injectPacket(packet)
        val delivered = fixture.engine.advanceUntilDeliveryOr(10_000)

        assertEquals(1, delivered.size)
        assertEquals(packet.id, delivered.first().id)
        assertEquals(fixture.node2.id, delivered.first().dstNodeId)

        // hop1: 1000ms tx + 10ms latency = 1010ms
        // hop2: 1000ms tx + 20ms latency = 1020ms
        // total = 2030ms
        assertEquals(2_030L, fixture.engine.currentTimeMillis)

        val noMore = fixture.engine.advanceUntilDeliveryOr(10_000)
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

        fixture.engine.injectPacket(packet)
        val delivered = fixture.engine.advanceUntilDeliveryOr(10_000)

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
        packets.forEach { fixture.engine.injectPacket(it) }

        val deliveredIds = mutableListOf<Long>()
        val deliveredTimes = mutableListOf<Long>()
        while (deliveredIds.size < packets.size) {
            val delivered = fixture.engine.advanceUntilDeliveryOr(10_000)
            assertTrue(delivered.isNotEmpty(), "Expected pending packets to eventually be delivered")
            deliveredIds += delivered.map { it.id }
            repeat(delivered.size) { deliveredTimes += fixture.engine.currentTimeMillis }
        }

        assertEquals(listOf(1L, 2L, 3L), deliveredIds)
        // hop latencies are 10ms + 20ms = 30ms, shaped at 1KB/s for 1KB packets
        assertEquals(listOf(30L, 1_030L, 2_030L), deliveredTimes)
    }
}
