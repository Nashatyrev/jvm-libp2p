package io.libp2p.quicsim.runner

import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.udpnetwork.udpSimDatagram
import io.libp2p.quicsim.udpnetwork.impl.CodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FqCodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl4
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class SimulatedScenarioNetworkTest {

    @Test
    fun `can use fq codel bandwidth queues`() {
        val network = QuicNetworkTopology.star(
            hostCount = 1,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = 1_000
        ).toUdpSimNetwork(BandwidthQueueDiscipline.FQ_CODEL)

        assertTrue(network.links.all { it.bandwidthQueue is FqCodelUdpSimBandwidthQueue })
    }

    @Test
    fun `can use codel bandwidth queues`() {
        val network = QuicNetworkTopology.star(
            hostCount = 1,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = 1_000
        ).toUdpSimNetwork(BandwidthQueueDiscipline.CODEL)

        assertTrue(network.links.all { it.bandwidthQueue is CodelUdpSimBandwidthQueue })
    }

    @Test
    fun `can use shadow like bandwidth queues`() {
        val network = QuicNetworkTopology.star(
            hostCount = 1,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = 1_000
        ).toUdpSimNetwork(BandwidthQueueDiscipline.SHADOW_LIKE)
        val outbound = network.links.single { it.from.id == "node-0" && it.to.id == "router-0" }
        val inbound = network.links.single { it.from.id == "router-0" && it.to.id == "node-0" }

        assertTrue(outbound.bandwidthQueue is FifoUdpSimBandwidthQueue)
        assertTrue(inbound.bandwidthQueue is CodelUdpSimBandwidthQueue)
    }

    @Test
    fun `impl4 can use fq codel bandwidth queues`() {
        val network = QuicNetworkTopology.star(
            hostCount = 2,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = 1_000_000
        ).toUdpSimNetwork(BandwidthQueueDiscipline.FQ_CODEL)
        val engine = UdpSimNetworkEngineImpl4(network)
        val packet = udpSimDatagram(100, "node-0", "node-1")
        val outbound = network.links.single { it.from.id == "node-0" && it.to.id == "router-0" }
        val inbound = network.links.single { it.from.id == "router-0" && it.to.id == "node-1" }

        outbound.latencyQueue.receiver.receivePackets(listOf(packet))

        engine.advanceUntil(10.milliseconds)
        inbound.latencyQueue.emitter.advanceAndExecuteAll(20.milliseconds)

        assertEquals(listOf(packet), inbound.latencyQueue.emitter.emitPackets())
    }
}
