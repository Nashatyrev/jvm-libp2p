package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.SerialPacketProcessor
import io.libp2p.quicsim.core.schedule.impl.LatencyQueueImpl
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl2
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl3
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl4
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class UdpNetworkPerformanceTest {

    @Disabled
    @Test
    @Tag("performance")
    fun `compares impl2 and impl3 real usage on all-to-all traffic`() {
//        assumeTrue(
//            java.lang.Boolean.getBoolean(PERFORMANCE_TEST_PROPERTY),
//            "Set -D$PERFORMANCE_TEST_PROPERTY=true to run this performance test"
//        )

        val impl2 = measureImpl2DirectUsage()
        val impl3 = measureSplitUsage("UdpSimNetworkEngineImpl3") { UdpSimNetworkEngineImpl3(it) }
        val impl4 = measureSplitUsage("UdpSimNetworkEngineImpl4") { UdpSimNetworkEngineImpl4(it) }
        val impl3ToImpl2Ratio = impl3.elapsedMs.toDouble() / impl2.elapsedMs.toDouble()
        val impl4ToImpl3Ratio = impl4.elapsedMs.toDouble() / impl3.elapsedMs.toDouble()

        println(
            "udpnetwork performance comparison: " +
                    "${impl2.name}=${impl2.elapsedMs}ms, " +
                    "${impl3.name}=${impl3.elapsedMs}ms, " +
                    "${impl4.name}=${impl4.elapsedMs}ms, " +
                    "impl3/impl2=${"%.2f".format(impl3ToImpl2Ratio)}x, " +
                    "impl4/impl3=${"%.2f".format(impl4ToImpl3Ratio)}x"
        )
    }

    @Test
    @Tag("performance")
    fun `impl4 sorts 200000 packets in extended advance`() {
        val (network, nodes) = createImpl3Network(Bandwidth(Long.MAX_VALUE))
        val endpointPorts = createEndpointPorts(network)
        val engine = UdpSimNetworkEngineImpl4(network)

        repeat(IMPL4_SORT_PACKET_COUNT) { packetIndex ->
            val src = nodes[packetIndex % nodes.size]
            val dst = nodes[(packetIndex * 31 + 1) % nodes.size]
            endpointPorts.getValue(src.id).deliver(listOf(createSortPacket(src, dst)))
        }

        val elapsedMs = measureTimeMillis {
            engine.advanceUntil(IMPL4_SORT_ADVANCE)
        }

        val deliveredPackets = endpointPorts.values.flatMap { it.advanceByWindow(IMPL4_SORT_ADVANCE) }

        assertEquals(IMPL4_SORT_PACKET_COUNT, deliveredPackets.size)
        assertTrue(
            elapsedMs < IMPL4_SORT_TARGET_MS,
            "Expected Impl4 to sort $IMPL4_SORT_PACKET_COUNT packets in under ${IMPL4_SORT_TARGET_MS}ms, actual=${elapsedMs}ms"
        )
        assertNull(engine.nextTaskDuration())
        endpointPorts.values.forEach {
            assertNull(it.nextTaskDuration())
        }

        println(
            "udpnetwork performance ${engine::class.simpleName}: sorted $IMPL4_SORT_PACKET_COUNT packets " +
                    "in ${elapsedMs}ms during extended advance"
        )
    }

    private fun measureImpl2DirectUsage(): BenchmarkResult {
        val (network, nodes) = createImpl2Network()
        val engine = UdpSimNetworkEngineImpl2(network)
        var packetId = 0L
        val deliveredPackets = mutableListOf<DatagramPacket>()

        var time = ZERO
        var stepCount = 0L
        val elapsedMs = measureTimeMillis {
            repeat(PACKETS_PER_PEER_PAIR) {
                val batch = ArrayList<DatagramPacket>(NODE_COUNT * SEND_TO_NODES)
                nodes.indices.forEach { srcIndex ->
                    val src = nodes[srcIndex]
                    repeat(SEND_TO_NODES) { idx ->
                        val dst = nodes[(srcIndex + idx + 1) % NODE_COUNT]
                        batch += createPacket(++packetId, src, dst)
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

        return BenchmarkResult("UdpSimNetworkEngineImpl2", elapsedMs, time, stepCount)
            .also { printResult(it, deliveredPackets.size) }
    }

    private fun measureSplitUsage(
        name: String,
        engineFactory: (UdpSimNetwork) -> UdpSimNetworkEngine
    ): BenchmarkResult {
        val (network, nodes) = createImpl3Network()
        val endpointPorts = createEndpointPorts(network)
        val engine = engineFactory(network)
        var packetId = 0L
        val deliveredPackets = mutableListOf<DatagramPacket>()

        var time = ZERO
        var stepCount = 0L
        val elapsedMs = measureTimeMillis {
            repeat(PACKETS_PER_PEER_PAIR) {
                nodes.indices.forEach { srcIndex ->
                    val src = nodes[srcIndex]
                    val batch = ArrayList<DatagramPacket>(SEND_TO_NODES)
                    repeat(SEND_TO_NODES) { idx ->
                        val dst = nodes[(srcIndex + idx + 1) % NODE_COUNT]
                        batch += createPacket(++packetId, src, dst)
                    }
                    deliveredPackets += endpointPorts.getValue(src.id).deliver(batch)
                }
            }

            while (deliveredPackets.size < EXPECTED_PACKET_COUNT) {
                check(stepCount < MAX_LATENCY_WINDOWS) {
                    "Benchmark did not deliver all packets after $MAX_LATENCY_WINDOWS latency windows: " +
                            "delivered=${deliveredPackets.size}, expected=$EXPECTED_PACKET_COUNT"
                }
                when (engine) {
                    is UdpSimNetworkEngineImpl3 -> engine.advanceUntil(LINK_LATENCY)
                    is UdpSimNetworkEngineImpl4 -> engine.advanceUntil(LINK_LATENCY)
                    else -> error("Unsupported split engine: ${engine::class.java.name}")
                }
                deliveredPackets += endpointPorts.values.flatMap { it.advanceByWindow(LINK_LATENCY) }
                time += LINK_LATENCY
                stepCount++
            }
        }

        assertEquals(EXPECTED_PACKET_COUNT, packetId)
        assertEquals(EXPECTED_PACKET_COUNT, deliveredPackets.size.toLong())
        assertNull(engine.nextTaskDuration())
        endpointPorts.values.forEach {
            assertNull(it.nextTaskDuration())
        }

        return BenchmarkResult(name, elapsedMs, time, stepCount)
            .also { printResult(it, deliveredPackets.size) }
    }

    private fun createPacket(
        packetId: Long,
        src: UdpSimNode,
        dst: UdpSimNode
    ): DatagramPacket {
        val packetSize = 1000 + (packetId % 500).toInt()
        return udpSimDatagram(packetSize, src.id, dst.id)
    }

    private fun createSortPacket(
        src: UdpSimNode,
        dst: UdpSimNode
    ): DatagramPacket =
        udpSimDatagram(1, src.id, dst.id)

    private fun createImpl2Network(): Pair<UdpSimNetwork, List<UdpSimNode>> =
        createNetwork { bandwidth, latency, _ ->
            selfContainedUdpSimQueue(bandwidth, latency)
        }

    private fun createImpl3Network(
        bandwidthOverride: Bandwidth? = null
    ): Pair<UdpSimNetwork, List<UdpSimNode>> =
        createNetwork { bandwidth, latency, isFromEndpoint ->
            val linkBandwidth = bandwidthOverride ?: bandwidth
            if (isFromEndpoint) {
                latencyThenBandwidthUdpSimQueue(linkBandwidth, latency)
            } else {
                fifoUdpSimQueue(linkBandwidth, latency)
            }
        }

    private fun createNetwork(
        qdiscFactory: (bandwidth: Bandwidth, latency: Duration, isFromEndpoint: Boolean) -> TestUdpSimQueue
    ): Pair<UdpSimNetwork, List<UdpSimNode>> {
        val builder = TestStarNetworkBuilder2()
        val nodes = List(NODE_COUNT) { index -> builder.node("node-$index") }
        var counter = 0L
        builder.linkAllToRouter(LINK_LATENCY) { latency, isFromEndpoint ->
            qdiscFactory(Bandwidth(50_000 + (++counter)), latency, isFromEndpoint)
        }
        return builder.build() to nodes
    }

    private fun createEndpointPorts(network: UdpSimNetwork): Map<String, EndpointPort> =
        network.nodes.associate { node ->
            val inboundLink = network.links.first { it.to == node }
            val outboundLink = network.links.first { it.from == node }
            node.id to EndpointPort(
                InOutProcessor(
                    emitter = inboundLink.latencyQueue.emitter,
                    receiver = outboundLink.latencyQueue.receiver
                )
            )
        }

    private fun selfContainedUdpSimQueue(
        bandwidth: Bandwidth,
        latency: Duration,
        maxQueueWaitTime: Duration = Duration.INFINITE
    ): TestUdpSimQueue {
        val bandwidthQueue = FifoUdpSimBandwidthQueue(bandwidth, maxQueueWaitTime)
        val latencyQueue = LatencyQueueImpl<DatagramPacket>(latency)
        return TestUdpSimQueue(
            bandwidthQueue = bandwidthQueue,
            latencyQueue = latencyQueue,
            delegate = SerialPacketProcessor(
                listOf(
                    bandwidthQueue,
                    InOutProcessor(latencyQueue.emitter, latencyQueue.receiver)
                )
            )
        )
    }

    private class EndpointPort(
        private val processor: InOutProcessor<DatagramPacket>
    ) {
        fun deliver(packets: List<DatagramPacket>): List<DatagramPacket> =
            processor.deliver(packets)

        fun advanceByWindow(window: Duration): List<DatagramPacket> {
            val deliveredPackets = mutableListOf<DatagramPacket>()
            var timeLeft = window
            while (timeLeft > ZERO) {
                val nextAdvance = minDuration(timeLeft, processor.nextTaskDuration() ?: timeLeft)
                processor.advanceAndExecuteAll(nextAdvance)
                deliveredPackets += processor.deliver(emptyList())
                timeLeft -= nextAdvance
            }
            return deliveredPackets
        }

        fun nextTaskDuration(): Duration? =
            processor.nextTaskDuration()
    }

    private data class BenchmarkResult(
        val name: String,
        val elapsedMs: Long,
        val simulatedTime: Duration,
        val stepCount: Long
    )

    private fun printResult(result: BenchmarkResult, deliveredPacketCount: Int) {
        println(
            "udpnetwork performance ${result.name}: delivered $deliveredPacketCount packets across " +
                    "$NODE_COUNT nodes in ${result.elapsedMs}ms, in sim ${result.simulatedTime}, " +
                    "with ${result.stepCount} steps"
        )
    }

    private companion object {
        const val PERFORMANCE_TEST_PROPERTY = "udpnetwork.performance"
        const val NODE_COUNT = 100
        const val SEND_TO_NODES = NODE_COUNT - 1
        const val PACKETS_PER_PEER_PAIR = 2
        const val EXPECTED_PACKET_COUNT = NODE_COUNT.toLong() * SEND_TO_NODES * PACKETS_PER_PEER_PAIR
        const val MAX_LATENCY_WINDOWS = 2_000L
        const val IMPL4_SORT_PACKET_COUNT = 200_000
        const val IMPL4_SORT_TARGET_MS = 200L
        val LINK_LATENCY = 10.milliseconds
        val IMPL4_SORT_ADVANCE = 1.seconds

        fun minDuration(first: Duration, second: Duration): Duration =
            if (first <= second) first else second
    }
}
