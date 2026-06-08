package io.libp2p.quicsim.runner

import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import org.junit.jupiter.api.Assertions.assertEquals
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
        val outbound = network.links.single { it.from.id == "node-0" && it.to.id == "router-0" }.qdisc
        val inbound = network.links.single { it.from.id == "router-0" && it.to.id == "node-0" }.qdisc
        val packet = UdpSimPacket(1, 100, "node-0", "router-0")

        outbound.deliver(listOf(packet))
        assertEquals(10.milliseconds, outbound.nextTaskDuration())

        inbound.deliver(listOf(packet))
        assertEquals(100.milliseconds, inbound.nextTaskDuration())
    }
}
