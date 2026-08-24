package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.core.schedule.impl.NanoMonotonicTimer
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.ContinentRegion
import io.libp2p.quicsim.scenario.RegionalNetworkDescriptor.Companion.WORLD_DESCRIPTOR_1
import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.scenario.VALIDATOR_BANDWIDTH_BYTES_PER_SECOND
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.udpSimDatagram
import io.libp2p.quicsim.udpnetwork.impl.CodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.FqCodelUdpSimBandwidthQueue
import io.libp2p.quicsim.udpnetwork.impl.UnshapedUdpSimBandwidthQueue
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class SimulatedScenarioNetworkTest {

    @Test
    @Timeout(5)
    fun `parallel timed controller respects node outbound bandwidth`() {
        val receivedAt = runParallelBandwidthProbe(
            sourceOutboundBandwidth = 1_000L,
            destinationInboundBandwidth = Long.MAX_VALUE
        )

        assertEquals(100.milliseconds, receivedAt[1] - receivedAt[0])
    }

    @Test
    @Timeout(5)
    fun `parallel timed controller respects node inbound bandwidth`() {
        val receivedAt = runParallelBandwidthProbe(
            sourceOutboundBandwidth = Long.MAX_VALUE,
            destinationInboundBandwidth = 1_000L
        )

        assertEquals(100.milliseconds, receivedAt[1] - receivedAt[0])
    }

    @Test
    @Timeout(5)
    fun `parallel timed controller shares node inbound bandwidth between sources`() {
        val topology = QuicNetworkTopology.star(
            hostCount = 3,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = Long.MAX_VALUE
        )
        val network = topology.copy(
            links = topology.links.map { link ->
                link.copy(
                    bandwidthBytesPerSecond =
                        if (link.from == "router-0" && link.to == "node-2") 1_000L else Long.MAX_VALUE
                )
            }
        ).toUdpSimNetwork(BandwidthQueueDiscipline.FIFO)
        val source0 = TestSimNode(
            ip = "node-0",
            outbound = mutableListOf(udpSimDatagram(100, "node-0", "node-2"))
        )
        val source1 = TestSimNode(
            ip = "node-1",
            outbound = mutableListOf(udpSimDatagram(100, "node-1", "node-2"))
        )
        val destination = TestSimNode(ip = "node-2")
        val controller = ParallelTimedNetworkController(
            simNet = TestSimNet(listOf(source0, source1, destination)),
            udpNet = network,
            parallelism = 2
        )

        controller.advanceWhile(predicate = { destination.receivedAt.size < 2 })

        assertEquals(100.milliseconds, destination.receivedAt[1] - destination.receivedAt[0])
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
    fun `regional router links bypass bandwidth queues`() {
        val network = QuicNetworkTopology.regional(
            descriptor = WORLD_DESCRIPTOR_1,
            hostRegions = listOf(ContinentRegion.US_EAST, ContinentRegion.EUROPE),
            bandwidthBytesPerSecond = VALIDATOR_BANDWIDTH_BYTES_PER_SECOND
        ).toUdpSimNetwork(BandwidthQueueDiscipline.FQ_CODEL)

        val routerLinks = network.links.filter {
            it.from.id.startsWith("router-") && it.to.id.startsWith("router-")
        }

        assertEquals(30, routerLinks.size)
        assertTrue(routerLinks.all { it.bandwidthQueue is UnshapedUdpSimBandwidthQueue })
    }

    private fun runParallelBandwidthProbe(
        sourceOutboundBandwidth: Long,
        destinationInboundBandwidth: Long
    ): List<Duration> {
        val topology = QuicNetworkTopology.star(
            hostCount = 2,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = Long.MAX_VALUE
        )
        val network = topology.copy(
            links = topology.links.map { link ->
                link.copy(
                    bandwidthBytesPerSecond = when {
                        link.from == "node-0" -> sourceOutboundBandwidth
                        link.to == "node-1" -> destinationInboundBandwidth
                        else -> Long.MAX_VALUE
                    }
                )
            }
        ).toUdpSimNetwork(BandwidthQueueDiscipline.FIFO)
        val source = TestSimNode(
            ip = "node-0",
            outbound = MutableList(2) {
                udpSimDatagram(bytes = 100, fromNodeId = "node-0", toNodeId = "node-1")
            }
        )
        val destination = TestSimNode(ip = "node-1")
        val controller = ParallelTimedNetworkController(
            simNet = TestSimNet(listOf(source, destination)),
            udpNet = network,
            parallelism = 2
        )

        controller.advanceWhile(predicate = { destination.receivedAt.size < 2 })

        return destination.receivedAt
    }

    private class TestSimNet(
        override val allNodes: List<SimNode<DatagramPacket>>
    ) : SimNet<DatagramPacket> {
        override fun receivePackets(packets: List<DatagramPacket>) {
        }
        override fun emitPackets(): List<DatagramPacket> = emptyList()
        override fun advance(advanceDuration: Duration) {
        }
        override fun executePending() {
        }
        override fun nextTaskDuration(): Duration? = null
    }

    private class TestSimNode(
        override val ip: String,
        private val outbound: MutableList<DatagramPacket> = mutableListOf()
    ) : SimNode<DatagramPacket> {
        private var elapsed = ZERO
        val receivedAt = mutableListOf<Duration>()

        override val nodeTime: MonotonicTimer = NanoMonotonicTimer { elapsed.inWholeNanoseconds }

        override fun receivePackets(packets: List<DatagramPacket>) {
            repeat(packets.size) {
                receivedAt += elapsed
            }
        }

        override fun emitPackets(): List<DatagramPacket> =
            outbound.toList().also { outbound.clear() }

        override fun advance(advanceDuration: Duration) {
            elapsed += advanceDuration
        }

        override fun executePending() {
        }

        override fun nextTaskDuration(): Duration? =
            if (outbound.isEmpty()) null else ZERO
    }
}
