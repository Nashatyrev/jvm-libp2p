package io.libp2p.quicsim.runner

import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.libp2p.quicsim.udpnetwork.impl.CodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FqCodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl4
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class SimulatedScenarioNetworkTest {

    @Test
    fun `keeps latency stage closest to host on outbound and inbound links`() {
        val network = QuicNetworkTopology.star(
            hostCount = 1,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = 1_000
        ).toUdpSimNetwork()
        val outbound = network.links.single { it.from.id == "node-0" && it.to.id == "router-0" }
        val inbound = network.links.single { it.from.id == "router-0" && it.to.id == "node-0" }
        val packet = UdpSimPacket(1, 100, "node-0", "router-0")

        outbound.latencyQueue.receiver.receivePackets(listOf(packet))
        assertEquals(10.milliseconds, outbound.qdisc.nextTaskDuration())

        inbound.qdisc.deliver(listOf(packet))
        assertEquals(null, inbound.qdisc.nextTaskDuration())
        assertEquals(10.milliseconds, inbound.latencyQueue.emitter.nextTaskDuration())
    }

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
    fun `impl4 can use fq codel bandwidth queues`() {
        val network = QuicNetworkTopology.star(
            hostCount = 2,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = 1_000_000
        ).toUdpSimNetwork(BandwidthQueueDiscipline.FQ_CODEL)
        val engine = UdpSimNetworkEngineImpl4(network)
        val packet = UdpSimPacket(1, 100, "node-0", "node-1")
        val outbound = network.links.single { it.from.id == "node-0" && it.to.id == "router-0" }
        val inbound = network.links.single { it.from.id == "router-0" && it.to.id == "node-1" }

        outbound.latencyQueue.receiver.receivePackets(listOf(packet))

        engine.advanceUntil(10.milliseconds)
        inbound.latencyQueue.emitter.advanceAndExecuteAll(20.milliseconds)

        assertEquals(listOf(packet), inbound.latencyQueue.emitter.emitPackets())
    }
}
