package io.libp2p.quicsim.udpnetwork

import io.libp2p.quicsim.udpnetwork.impl.ParallelUdpSimNetworkEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ParallelUdpSimNetworkEngineTest {

    @Test
    fun `does not deliver endpoint-bound packet before inbound latency elapses`() {
        val builder = TestStarNetworkBuilder2()
        builder.node("node-0")
        builder.node("node-1")
        builder.linkAllToRouter(
            100.milliseconds,
            qdiscFactory = { latency -> fifoUdpSimQueue(Bandwidth(1_000_000), latency) }
        )
        val network = builder.build()
        val inboundLatencyQueue = network.links.first { it.to.id == "node-1" }.latencyQueue
        val inboundAheadReader = inboundLatencyQueue.aheadProcessor
        val engine = ParallelUdpSimNetworkEngine(network, drainEndpointBoundLatency = false)
        val packet = UdpSimPacket(
            id = 1,
            bytes = 100,
            srcNodeId = "node-0",
            dstNodeId = "node-1",
        )

        inboundAheadReader.advanceAndExecuteAll(100.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), engine.deliver(listOf(packet)))

        engine.advanceAndExecuteUntil(100.milliseconds)
        assertEquals(emptyList<UdpSimPacket>(), inboundAheadReader.deliver(emptyList()))

        inboundAheadReader.advanceAndExecuteAll(100.milliseconds)
        assertEquals(listOf(packet), inboundAheadReader.deliver(emptyList()))
    }

    @Test
    fun `delivers packets according to endpoint latency and world-side bandwidth`() {
        val builder = TestStarNetworkBuilder2()
        builder.node("node-0")
        builder.node("node-1")
        builder.linkAllToRouter(
            100.milliseconds,
            qdiscFactory = { latency -> fifoUdpSimQueue(Bandwidth(1_000), latency) }
        )
        val network = builder.build()
        val outboundAheadWriter = network.links.first { it.from.id == "node-0" }.latencyQueue.aheadEnqueueProcessor
        val inboundAheadReader = network.links.first { it.to.id == "node-1" }.latencyQueue.aheadProcessor
        val engine = ParallelUdpSimNetworkEngine(network, drainEndpointBoundLatency = false)
        val packet1 = packet(1)
        val packet2 = packet(2)
        val packet3 = packet(3)
        var currentTime = Duration.ZERO

        fun advanceTo(time: Duration): List<UdpSimPacket> {
            val advance = time - currentTime
            outboundAheadWriter.advanceAndExecuteAll(advance)
            inboundAheadReader.advanceAndExecuteAll(advance)
            engine.advanceAndExecuteUntil(advance)
            currentTime = time
            return inboundAheadReader.deliver(emptyList())
        }

        assertEquals(emptyList<UdpSimPacket>(), outboundAheadWriter.deliver(listOf(packet1)))

        assertEquals(emptyList<UdpSimPacket>(), advanceTo(50.milliseconds))
        assertEquals(emptyList<UdpSimPacket>(), outboundAheadWriter.deliver(listOf(packet2)))

        assertEquals(emptyList<UdpSimPacket>(), advanceTo(100.milliseconds))
        assertEquals(emptyList<UdpSimPacket>(), advanceTo(150.milliseconds))
        assertEquals(listOf(packet1), advanceTo(200.milliseconds))

        assertEquals(listOf(packet2), advanceTo(250.milliseconds))
        assertEquals(emptyList<UdpSimPacket>(), outboundAheadWriter.deliver(listOf(packet3)))

        assertEquals(emptyList<UdpSimPacket>(), advanceTo(300.milliseconds))
        assertEquals(emptyList<UdpSimPacket>(), advanceTo(350.milliseconds))
        assertEquals(emptyList<UdpSimPacket>(), advanceTo(400.milliseconds))
        assertEquals(listOf(packet3), advanceTo(450.milliseconds))
    }

    private fun packet(id: Long): UdpSimPacket =
        UdpSimPacket(
            id = id,
            bytes = 50,
            srcNodeId = "node-0",
            dstNodeId = "node-1",
        )
}
