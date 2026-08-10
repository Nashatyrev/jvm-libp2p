package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.DispatchingPacketProcessor2
import io.libp2p.quicsim.core.schedule.impl.SimpleMonotonicTimer
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.TestNetworkBuilder
import io.libp2p.quicsim.udpnetwork.TestQDiscFactory
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.fifoUdpSimQueue
import io.netty.buffer.Unpooled
import io.netty.channel.socket.DatagramPacket
import io.netty.util.ReferenceCountUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds

class ParallelTimedNetworkControllerTest {

    @Test
    fun `does not execute idle endpoint before inbound packet is due`() {
        val networkBuilder = TestNetworkBuilder()
        val router = networkBuilder.router("router")
        val sourceNode = networkBuilder.node("10.0.0.0")
        val destinationNode = networkBuilder.node("10.0.0.1")
        val qdiscFactory = fifoQDiscFactory(Bandwidth(Long.MAX_VALUE))
        networkBuilder.linkBiDir(sourceNode, router, 10.milliseconds, qdiscFactory)
        networkBuilder.linkBiDir(destinationNode, router, 10.milliseconds, qdiscFactory)
        val source = RecordingSimNode(
            ip = sourceNode.id,
            scheduledPackets = listOf(
                ScheduledPacket(200.milliseconds, destinationNode.id, sequence = 0, bytes = 16)
            )
        )
        val destination = RecordingSimNode(destinationNode.id, emptyList())
        val controller = ParallelTimedNetworkController(
            simNet = RecordingSimNet(listOf(source, destination)),
            udpNet = networkBuilder.build(),
            routeResolver = networkBuilder.routeResolver(),
            parallelism = 2
        )

        controller.advanceWhile { destination.receivedPackets.isEmpty() }

        assertEquals(220.milliseconds, destination.receivedPackets.single().receivedAt)
        assertEquals(220.milliseconds, destination.advanceDurations.first())
        assertEquals(230.milliseconds, destination.advanceDurations.fold(ZERO) { total, advance -> total + advance })
    }

    @Test
    @Timeout(10)
    fun `respects latency and bandwidth across two routers and four nodes`() {
        val networkBuilder = TestNetworkBuilder()
        val router1 = networkBuilder.router("router-1")
        val router2 = networkBuilder.router("router-2")
        val endpoints = listOf(
            EndpointConfig(0, networkBuilder.node("10.0.0.0"), router1, 20.milliseconds, Bandwidth(50_000)),
            EndpointConfig(1, networkBuilder.node("10.0.0.1"), router1, 30.milliseconds, Bandwidth(40_000)),
            EndpointConfig(2, networkBuilder.node("10.0.0.2"), router2, 40.milliseconds, Bandwidth(25_000)),
            EndpointConfig(3, networkBuilder.node("10.0.0.3"), router2, 50.milliseconds, Bandwidth(20_000)),
        )
        endpoints.forEach { endpoint ->
            networkBuilder.linkBiDir(
                endpoint.node,
                endpoint.router,
                endpoint.latency,
                fifoQDiscFactory(endpoint.bandwidth)
            )
        }
        networkBuilder.linkBiDir(
            router1,
            router2,
            100.milliseconds,
            fifoQDiscFactory(Bandwidth(Long.MAX_VALUE))
        )
        val packetBytes = 1_000
        val pairInterval = 300.milliseconds
        val routeChecks = endpoints
            .flatMap { from -> endpoints.filter { it != from }.map { to -> from to to } }
            .mapIndexed { index, (from, to) ->
                val sendAt = 200.milliseconds + pairInterval * index
                RouteCheck(
                    from = from,
                    to = to,
                    sendAt = sendAt,
                    firstReceivedAt = sendAt + pathLatency(from, to),
                    secondReceivedAt = sendAt + pathLatency(from, to) + pathBottleneckDelay(from, to, packetBytes)
                )
            }
        val nodes = endpoints.map { endpoint ->
            RecordingSimNode(
                ip = endpoint.ip,
                scheduledPackets = routeChecks
                    .filter { it.from == endpoint }
                    .flatMap { check ->
                        listOf(
                            ScheduledPacket(check.sendAt, check.to.ip, sequence = 0, bytes = packetBytes),
                            ScheduledPacket(check.sendAt, check.to.ip, sequence = 1, bytes = packetBytes)
                        )
                    }
            )
        }
        val controller = ParallelTimedNetworkController(
            simNet = RecordingSimNet(nodes),
            udpNet = networkBuilder.build(),
            routeResolver = networkBuilder.routeResolver(),
            parallelism = 4
        )
        var iterations = 0

        controller.advanceWhile {
            check(iterations++ < 1_000_000) {
                "Timed network did not deliver all packets; " +
                        "received=${nodes.sumOf { it.receivedPackets.size }} " +
                        "vertexTimes=${controller.timedGraph.vertices.associate { it.id to it.time }}"
            }
            nodes.sumOf { it.receivedPackets.size } < routeChecks.size * 2
        }

        val receipts = nodes
            .flatMap { it.receivedPackets }
            .associateBy { PacketKey(it.fromIp, it.toIp, it.sequence) }
        routeChecks.forEach { check ->
            assertEquals(
                check.firstReceivedAt,
                receipts.getValue(PacketKey(check.from.ip, check.to.ip, 0)).receivedAt,
                "${check.from.ip}->${check.to.ip} first packet"
            )
            assertEquals(
                check.secondReceivedAt,
                receipts.getValue(PacketKey(check.from.ip, check.to.ip, 1)).receivedAt,
                "${check.from.ip}->${check.to.ip} second packet"
            )
        }
    }

    private data class EndpointConfig(
        val id: Int,
        val node: UdpSimNode,
        val router: UdpSimNode,
        val latency: Duration,
        val bandwidth: Bandwidth,
    ) {
        val ip: String get() = node.id
    }

    private data class RouteCheck(
        val from: EndpointConfig,
        val to: EndpointConfig,
        val sendAt: Duration,
        val firstReceivedAt: Duration,
        val secondReceivedAt: Duration,
    )

    private data class ScheduledPacket(
        val at: Duration,
        val toIp: String,
        val sequence: Int,
        val bytes: Int,
    )

    private data class ReceivedPacket(
        val fromIp: String,
        val toIp: String,
        val sequence: Int,
        val receivedAt: Duration,
    )

    private data class PacketKey(
        val fromIp: String,
        val toIp: String,
        val sequence: Int,
    )

    private class RecordingSimNet(
        override val allNodes: List<RecordingSimNode>
    ) : SimNet<DatagramPacket> {
        private val dispatcher = DispatchingPacketProcessor2(allNodes.associateBy { it.ip }) {
            it.recipient().hostString
        }

        override fun receivePackets(packets: List<DatagramPacket>) =
            dispatcher.receivePackets(packets)

        override fun emitPackets(): List<DatagramPacket> =
            dispatcher.emitPackets()

        override fun advance(advanceDuration: Duration) =
            dispatcher.advance(advanceDuration)

        override fun executePending() =
            dispatcher.executePending()

        override fun nextTaskDuration(): Duration? =
            dispatcher.nextTaskDuration()
    }

    private class RecordingSimNode(
        override val ip: String,
        scheduledPackets: List<ScheduledPacket>,
    ) : SimNode<DatagramPacket> {
        override val nodeTime = SimpleMonotonicTimer()
        val receivedPackets = mutableListOf<ReceivedPacket>()
        val advanceDurations = mutableListOf<Duration>()
        private val outboundPackets = scheduledPackets.toMutableList()

        override fun receivePackets(packets: List<DatagramPacket>) {
            packets.forEach { packet ->
                try {
                    receivedPackets += ReceivedPacket(
                        fromIp = packet.sender().hostString,
                        toIp = packet.recipient().hostString,
                        sequence = packet.content().readInt(),
                        receivedAt = nodeTime.curT,
                    )
                } finally {
                    ReferenceCountUtil.safeRelease(packet)
                }
            }
        }

        override fun emitPackets(): List<DatagramPacket> {
            val readyPackets = outboundPackets.filter { it.at <= nodeTime.curT }
            outboundPackets.removeAll(readyPackets)
            return readyPackets.map { packet ->
                DatagramPacket(
                    Unpooled.buffer(packet.bytes, packet.bytes).also {
                        it.writeInt(packet.sequence)
                        it.writeZero(packet.bytes - Int.SIZE_BYTES)
                    },
                    udpAddress(packet.toIp),
                    udpAddress(ip)
                )
            }
        }

        override fun advance(advanceDuration: Duration) {
            advanceDurations += advanceDuration
            nodeTime.curT += advanceDuration
        }

        override fun executePending() {
        }

        override fun nextTaskDuration(): Duration? =
            outboundPackets.minOfOrNull { (it.at - nodeTime.curT).coerceAtLeast(ZERO) }

        private fun udpAddress(ip: String): InetSocketAddress =
            InetSocketAddress.createUnresolved(ip, 1)
    }

    private fun pathLatency(from: EndpointConfig, to: EndpointConfig): Duration =
        from.latency + (if (from.router == to.router) ZERO else 100.milliseconds) + to.latency

    private fun pathBottleneckDelay(from: EndpointConfig, to: EndpointConfig, packetBytes: Int): Duration =
        listOf(
            from.bandwidth.durationToTransfer(packetBytes),
            to.bandwidth.durationToTransfer(packetBytes)
        ).maxOrNull()!!

    private fun fifoQDiscFactory(bandwidth: Bandwidth): TestQDiscFactory = { latency, _ ->
        fifoUdpSimQueue(bandwidth, latency)
    }

}
