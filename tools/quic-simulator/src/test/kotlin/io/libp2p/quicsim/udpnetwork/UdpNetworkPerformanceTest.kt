package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.milliseconds

class UdpNetworkPerformanceTest {

    @Test
    @Tag("performance")
    fun `measures 500 node all-to-all traffic with 10 packets per peer`() {
//        assumeTrue(
//            java.lang.Boolean.getBoolean(PERFORMANCE_TEST_PROPERTY),
//            "Set -D$PERFORMANCE_TEST_PROPERTY=true to run this performance test"
//        )

        val builder = TestStarNetworkBuilder2()
        val nodes = List(NODE_COUNT) { index -> builder.node("node-$index") }
        var counter = 0L
        builder.linkAllToRouter(10.milliseconds) { latency ->
            fifoUdpSimQueue(Bandwidth(50_000 + (++counter)), latency)
        }
        val engine = UdpSimNetworkEngineImpl2(builder.build())

        var packetId = 0L
        val deliveredPackets = mutableListOf<UdpSimPacket>()

        var time = kotlin.time.Duration.ZERO
        var stepCount = 0L
        val elapsedMs = measureTimeMillis {
            repeat(PACKETS_PER_PEER_PAIR) {
                val batch = ArrayList<UdpSimPacket>(NODE_COUNT * (NODE_COUNT - 1))
                nodes.indices.forEach { srcIndex ->
                    val src = nodes[srcIndex]
                    repeat(SEND_TO_NODES) { idx ->
                        val dstIndex = (srcIndex + idx + 1) % NODE_COUNT
                        val dst = nodes[dstIndex]
                        val packetSize = 1000 + (packetId % 500).toInt()
                        batch += UdpSimPacket(++packetId, packetSize, src.id, dst.id)
                    }
                }
                deliveredPackets += engine.deliver(batch)
            }

            while (deliveredPackets.size < EXPECTED_PACKET_COUNT) {
                val nextTaskDuration = engine.nextTaskDuration()!!
                time += nextTaskDuration
                engine.advanceAndExecuteAll(nextTaskDuration)
                deliveredPackets += engine.deliver(emptyList())
                stepCount++
            }
        }

        assertEquals(EXPECTED_PACKET_COUNT, packetId)
        assertEquals(EXPECTED_PACKET_COUNT, deliveredPackets.size.toLong())
        assertNull(engine.nextTaskDuration())

        println(
            "udpnetwork performance: delivered ${deliveredPackets.size} packets across " +
                    "$NODE_COUNT nodes in ${elapsedMs}ms, in sim $time, with  $stepCount steps"
        )
    }

    private companion object {
        const val PERFORMANCE_TEST_PROPERTY = "udpnetwork.performance"
        const val NODE_COUNT = 200
        const val SEND_TO_NODES = NODE_COUNT - 1
        const val PACKETS_PER_PEER_PAIR = 2
        const val EXPECTED_PACKET_COUNT = NODE_COUNT.toLong() * SEND_TO_NODES * PACKETS_PER_PEER_PAIR
        val FAST_BANDWIDTH = Bandwidth(Long.MAX_VALUE)
    }
}
