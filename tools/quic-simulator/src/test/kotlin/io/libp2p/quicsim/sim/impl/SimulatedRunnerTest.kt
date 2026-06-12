package io.libp2p.quicsim.sim.impl

import io.libp2p.core.Host
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.core.PacketProcessorVisitor
import io.libp2p.quicsim.core.schedule.impl.submitAfterDelay
import io.libp2p.quicsim.program.DataChunkMetrics
import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.program.SampleGossipNodeProgram
import io.libp2p.quicsim.runner.SimulatedRunner
import io.libp2p.quicsim.runner.SimulatedQuicScenarioRunner
import io.libp2p.quicsim.scenario.QuicScenarios
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.udpnetwork.Bandwidth
import io.libp2p.quicsim.udpnetwork.TestStarNetworkBuilder2
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.libp2p.quicsim.udpnetwork.impl.BasicUdpSimNetwork
import io.libp2p.quicsim.udpnetwork.TestUdpSimQueue
import io.libp2p.quicsim.udpnetwork.fifoUdpSimQueue
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl2
import io.netty.buffer.ByteBuf
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SimulatedRunnerTest {
    private data class TransferMetrics(
        val receivedSize: Int,
        val sentAtSimMillis: Long,
        val receivedAtSimMillis: Long
    ) {
        val simulatedDeltaMillis: Long
            get() = receivedAtSimMillis - sentAtSimMillis
    }

    @Test
    fun `simulated runner completes 5-node ring with sample gossip`() {
        val nodeCount = 5
        val publisherCount = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val networkBuilder = TestStarNetworkBuilder2()
        (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = fifoQDiscFactory(Bandwidth(1_000_000L))
        networkBuilder.linkAllToRouter(50.milliseconds, qdiscFactory)

        val runner = SimulatedRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: SimNodeId) =
                    SampleGossipNodeProgram(
                        simNodeId = id,
                        connectToNodeIds = listOf((id + 1) % nodeCount),
                        publishersCount = publisherCount,
                        params = GossipParams(),
                        randomSeed = id.toLong(),
                        messageSizeBytes = 128 * 1024,
                        initialPublishDelay = 5.seconds
                    ).also { nodePrograms += it }
            },
            udpNetwork = networkBuilder.build()
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.completeFuture.isDone },
            "Expected all sample gossip node programs to complete"
        )
    }

    @Test
    fun sendMessageFromNPublishers() {
        val nodeCount = 100
        val publishersCount = 100
        val neighboursToConnect = 20
        val bandwidth = Bandwidth(5_000_000L)
        val halfLatency = 50.milliseconds
        val messageSizeBytes = 1024
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val packetStats = PacketStatsNodeVisitorFactory()
        val randomConnectionsByNode: Map<SimNodeId, List<SimNodeId>> =
            QuicScenarios.createBidirectionalRandomTopology(nodeCount, neighboursToConnect, seed = 1234)

        val networkBuilder = TestStarNetworkBuilder2()
        (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = fifoQDiscFactory(bandwidth)
        networkBuilder.linkAllToRouter(halfLatency, qdiscFactory).build()
        val udpNetwork = networkBuilder.build()

        val runner = SimulatedRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: SimNodeId): NodeProgram =
                    SampleGossipNodeProgram(
                        simNodeId = id,
                        connectToNodeIds = randomConnectionsByNode.getValue(id),
                        publishersCount = publishersCount,
                        params = GossipParams(
//                            // switch off IHAVE
//                            gossipFactor = 0.0,
//                            DLazy = 0,
                            ),
                        randomSeed = id.toLong(),
                        messageSizeBytes = messageSizeBytes,
                        initialPublishDelay = 30.seconds,
                    ).also { nodePrograms += it }
            },
            udpNetwork = udpNetwork,
            maxSimulatedRunDuration = 10.minutes,
            latencyWindowParallelism = 1,
            nodeVisitorFactory = packetStats
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.completeFuture.isDone },
            "Expected all sample gossip node programs to complete in 1000-node scenario"
        )

//        println("Total packet count: " + udpNetworkLogging.packetsCount + ", bytes: " + udpNetworkLogging.throughputBytes)
        println("Params: neighboursToConnect: $neighboursToConnect, publishersCount: $publishersCount")
        println("Packet stats: ${packetStats.snapshot()}")
    }

    @Test
    @Timeout(30)
    fun `simulated runner transfers fixed-size message over primitive protocol`() {
        val messageSize = 1_000
        val linkLatencyMs = 25L
        val metrics = runFixedMessageScenario("x".repeat(messageSize), linkLatencyMs, 1_000_000L)
        assertEquals(messageSize, metrics.receivedSize)
        assertTrue(metrics.sentAtSimMillis >= 0, "Expected sender to record send timestamp")
        assertTrue(metrics.receivedAtSimMillis >= 0, "Expected receiver to record receive timestamp")
        assertTrue(
            metrics.simulatedDeltaMillis >= linkLatencyMs * 2,
            "Expected request/response to reflect at least round-trip link latency in simulated millis"
        )
    }

    @Test
    @Timeout(30)
    fun `latency window simulated runner transfers fixed-size message over primitive protocol`() {
        val messageSize = 1_000
        val linkLatencyMs = 25L
        val metrics = runFixedMessageScenario(
            payload = "w".repeat(messageSize),
            linkLatencyMs = linkLatencyMs,
            bandwidthBytesPerSec = 1_000_000L,
            latencyWindowParallelism = 2
        )

        assertEquals(messageSize, metrics.receivedSize)
        assertTrue(metrics.sentAtSimMillis >= 0, "Expected sender to record send timestamp")
        assertTrue(metrics.receivedAtSimMillis >= 0, "Expected receiver to record receive timestamp")
        assertTrue(
            metrics.simulatedDeltaMillis >= linkLatencyMs * 2,
            "Expected request/response to reflect at least round-trip link latency in simulated millis"
        )
    }

    @Test
    @Timeout(30)
    fun `data chunk node program factory records flushed packet receive timestamps`() {
        val nodeCount = 3
        val factory = DataChunkNodeProgramFactory(
            nodeCount = nodeCount,
            chunks = listOf(
                DataChunkNodeProgramFactory.DataChunk(
                    sizeBytes = 2_500,
                    at = 100.milliseconds,
                    from = 0,
                    to = 1
                ),
                DataChunkNodeProgramFactory.DataChunk(
                    sizeBytes = 1_000,
                    at = 150.milliseconds,
                    from = 2,
                    to = 0
                )
            )
        )

        val builder = TestStarNetworkBuilder2()
        (0 until nodeCount).forEach { builder.node("node-$it") }
        builder.linkAllToRouter(
            10.milliseconds,
            qdiscFactory = fifoQDiscFactory(Bandwidth(1_000_000L))
        )

        val runner = SimulatedRunner(
            nodeFactory = factory,
            udpNetwork = builder.build(),
            maxSimulatedRunDuration = 5.seconds
        )

        try {
            runner.run()
        } catch (t: Throwable) {
            println(factory.debugState())
            throw t
        }

        val firstChunkReceipts = factory.packetReceipts(0)
        val secondChunkReceipts = factory.packetReceipts(1)
        assertEquals(3, firstChunkReceipts.size)
        assertEquals(1, secondChunkReceipts.size)
        assertEquals(listOf(0, 1, 2), firstChunkReceipts.map { it.sequence }.sorted())
        assertTrue(firstChunkReceipts.all { it.from == 0 && it.to == 1 })
        assertTrue(secondChunkReceipts.all { it.from == 2 && it.to == 0 })
        assertTrue(factory.packetReceipts().all { !it.receivedAt.isNegative() })
    }

    @Test
    @Timeout(30)
    fun `data chunk node program 2 nodes`() {
        val nodeCount = 2
        val factory = DataChunkNodeProgramFactory(
            nodeCount = nodeCount,
            chunks = listOf(
                DataChunkNodeProgramFactory.DataChunk(
                    sizeBytes = 1_000_000,
                    at = 10.seconds,
                    from = 0,
                    to = 1
                ),
            )
        )

        val builder = TestStarNetworkBuilder2()
        (0 until nodeCount).forEach { builder.node("node-$it") }
        builder.linkAllToRouter(
            latency = 100.milliseconds,
            qdiscFactory = fifoQDiscFactory(Bandwidth(1_000_000L))
        )

        val runner = SimulatedRunner(
            nodeFactory = factory,
            udpNetwork = builder.build(),
            maxSimulatedRunDuration = 100.seconds
        )

        try {
            runner.run()
        } catch (t: Throwable) {
            println(factory.debugState())
            throw t
        }

        val firstChunkReceipts = factory.packetReceipts(0)
//        firstChunkReceipts.forEach {
//            println("${it.receivedAt.inWholeMilliseconds}\t${it.sequence}\t${it.totalPackets}")
//        }
        assertEquals(1_000, firstChunkReceipts.size)
        assertExponentialLikePacketFlights(firstChunkReceipts)
    }

    private fun assertExponentialLikePacketFlights(
        receipts: List<DataChunkNodeProgramFactory.PacketReceipt>,
        interFlightGapMillis: Long = 100,
    ) {
        val flights = receipts
            .sortedWith(compareBy<DataChunkNodeProgramFactory.PacketReceipt> { it.receivedAt }.thenBy { it.sequence })
            .fold(mutableListOf<MutableList<DataChunkNodeProgramFactory.PacketReceipt>>()) { grouped, receipt ->
                val previousReceipt = grouped.lastOrNull()?.lastOrNull()
                if (
                    previousReceipt == null ||
                    receipt.receivedAt.inWholeMilliseconds - previousReceipt.receivedAt.inWholeMilliseconds > interFlightGapMillis
                ) {
                    grouped += mutableListOf(receipt)
                } else {
                    grouped.last() += receipt
                }
                grouped
            }

        val firstSlowStartFlights = flights.take(4).map { it.size }
        assertTrue(
            firstSlowStartFlights.size == 4,
            "Expected at least 4 packet flights, got ${flights.map { it.size }}"
        )
        firstSlowStartFlights.zipWithNext().forEach { (previous, next) ->
            assertTrue(
                next >= previous * 3 / 2,
                "Expected exponential-like packet flight growth, got ${flights.map { it.size }}"
            )
        }
    }

    @Test
    @Timeout(30)
    fun `check QUIC slow start`() {
        val result = SimulatedQuicScenarioRunner().run(QuicScenarios.slowStart())

        val firstChunkReceipts = DataChunkMetrics.packetReceipts(result.events)
        firstChunkReceipts.forEach {
            println("${it.receivedAt.inWholeMilliseconds}\t${it.sequence}\t${it.totalPackets}")
        }
    }


    @Test
    fun `2 nodes connect to each other`() {
        val builder = TestStarNetworkBuilder2()
        builder.node("node-0")
        builder.node("node-1")
        builder.linkAllToRouter(
            100.milliseconds,
            qdiscFactory = fifoQDiscFactory(Bandwidth(10_000L))
        )

        val runner = SimulatedRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: SimNodeId): NodeProgram =
                    object : NodeProgram {
                        override val simNodeId: SimNodeId = id
                        override val completeFuture: CompletableFuture<Unit> = CompletableFuture()
                        private var myHost: Host? = null

                        override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> {
                            return emptyList()
                        }

                        override fun start(
                            simContext: SimContext,
                            networkContext: NetworkContext
                        ): CompletableFuture<Unit> {
                            myHost = networkContext.myHost
                            return if (simNodeId == 0) {
                                networkContext.myHost.network
                                    .connect(networkContext.allNodes[1]!!)
                                    .thenApply {
                                        completeFuture.complete(Unit)
                                        Unit
                                    }
                            } else {
                                completeFuture.complete(Unit)
                                CompletableFuture.completedFuture(Unit)
                            }
                        }

                    }
            },
            udpNetwork = builder.build(),
            nodeVisitorFactory = { NodeLogger(it) },
            latencyWindowParallelism = 1
        )

        runner.run()
    }

    @Test
    @Timeout(30)
    fun `simulated runner reflects larger message size with longer simulated delivery time`() {
        val linkLatencyMs = 10L
        val bandwidthBytesPerSec = 50_000L
        val small = runFixedMessageScenario("s".repeat(1_024), linkLatencyMs, bandwidthBytesPerSec)
        val large = runFixedMessageScenario("l".repeat(16_384), linkLatencyMs, bandwidthBytesPerSec)

        assertEquals(1_024, small.receivedSize)
        assertEquals(16_384, large.receivedSize)
        assertTrue(
            large.simulatedDeltaMillis > small.simulatedDeltaMillis,
            "Expected larger message to take longer: small=${small.simulatedDeltaMillis}ms " +
                    "large=${large.simulatedDeltaMillis}ms"
        )
    }

    private fun runFixedMessageScenario(
        payload: String,
        linkLatencyMs: Long,
        bandwidthBytesPerSec: Long,
        latencyWindowParallelism: Int = 0,
    ): TransferMetrics {
        val sentAtSimMillis = AtomicLong(-1L)
        val receivedAtSimMillis = AtomicLong(-1L)
        val receivedSize = CompletableFuture<Int>()

        val builder = TestStarNetworkBuilder2()
        builder.node("node-0")
        builder.node("node-1")
        builder.linkAllToRouter(
            linkLatencyMs.milliseconds,
            qdiscFactory = fifoQDiscFactory(Bandwidth(bandwidthBytesPerSec))
        )

        val runner = SimulatedRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: SimNodeId): NodeProgram =
                    if (id == 0) {
                        FixedMessageSenderProgram(
                            simNodeId = id,
                            targetNodeId = 1,
                            payload = payload,
                            sentAtSimMillis = sentAtSimMillis,
                            receivedAtSimMillis = receivedAtSimMillis,
                            receivedSize = receivedSize
                        )
                    } else {
                        PassiveEchoNodeProgram(simNodeId = id)
                    }
            },
            udpNetwork = builder.build(),
            latencyWindowParallelism = latencyWindowParallelism,
        )

        runner.run()

        return TransferMetrics(
            receivedSize = receivedSize.get(5, TimeUnit.SECONDS),
            sentAtSimMillis = sentAtSimMillis.get(),
            receivedAtSimMillis = receivedAtSimMillis.get()
        )
    }

    private class SimpleConnectNodeProgram(
        override val simNodeId: SimNodeId
    ) : NodeProgram {
        override val completeFuture: CompletableFuture<Unit> = CompletableFuture()

        override fun createProtocols(context: SimContext) = emptyList<io.libp2p.core.multistream.ProtocolBinding<*>>()

        override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
            return simContext.scheduler.submitAfterDelay(100.milliseconds) {
                completeFuture.complete(Unit)
            }
        }
    }

    private companion object {
        fun fifoQDiscFactory(bandwidth: Bandwidth): (kotlin.time.Duration) -> TestUdpSimQueue = { latency ->
            fifoUdpSimQueue(
                bandwidth = bandwidth,
                latency = latency
            )
        }
    }

    private class FixedMessageSenderProgram(
        override val simNodeId: SimNodeId,
        private val targetNodeId: SimNodeId,
        private val payload: String,
        private val sentAtSimMillis: AtomicLong,
        private val receivedAtSimMillis: AtomicLong,
        private val receivedSize: CompletableFuture<Int>
    ) : NodeProgram {
        override val completeFuture: CompletableFuture<Unit> = CompletableFuture()
        private lateinit var binding: SizeEchoBinding

        override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> {
            binding = SizeEchoBinding(SizeEchoProtocol())
            return listOf(binding)
        }

        override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
            val epoch = simContext.timer.time()
            val addr =
                networkContext.allNodes[targetNodeId] ?: throw IllegalStateException("Node $targetNodeId not found")

            return networkContext.myHost.network.connect(addr)
                .thenCompose { conn ->
                    conn.muxerSession().createStream(binding).controller
                }
                .thenCompose { ctrl ->
                    sentAtSimMillis.set((simContext.timer.time() - epoch).inWholeMilliseconds)
                    ctrl.send(payload.toByteArray(StandardCharsets.UTF_8))
                }
                .handle { echoedBytes, err ->
                    if (err == null) {
                        receivedAtSimMillis.set((simContext.timer.time() - epoch).inWholeMilliseconds)
                        receivedSize.complete(echoedBytes.size)
                        completeFuture.complete(Unit)
                    } else {
                        completeFuture.completeExceptionally(err)
                    }
                }
        }
    }

    private class PassiveEchoNodeProgram(
        override val simNodeId: SimNodeId
    ) : NodeProgram {
        override val completeFuture: CompletableFuture<Unit> = CompletableFuture()

        override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> =
            listOf(SizeEchoBinding(SizeEchoProtocol()))

        override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
            completeFuture.complete(Unit)
            return CompletableFuture.completedFuture(Unit)
        }
    }

    private interface SizeEchoController {
        fun send(payload: ByteArray): CompletableFuture<ByteArray>
    }

    private class SizeEchoBinding(protocol: SizeEchoProtocol) :
        StrictProtocolBinding<SizeEchoController>("/quicsim/size-echo/1.0.0", protocol)

    private class SizeEchoProtocol : ProtocolHandler<SizeEchoController>(Long.MAX_VALUE, Long.MAX_VALUE) {
        override fun onStartInitiator(stream: Stream): CompletableFuture<SizeEchoController> {
            val ready = CompletableFuture<Void>()
            val controller = InitiatorController(ready)
            stream.pushHandler(controller)
            return ready.thenApply { controller }
        }

        override fun onStartResponder(stream: Stream): CompletableFuture<SizeEchoController> {
            val controller = ResponderController()
            stream.pushHandler(controller)
            return CompletableFuture.completedFuture(controller)
        }

        private class ResponderController : ProtocolMessageHandler<ByteBuf>, SizeEchoController {
            override fun onMessage(stream: Stream, msg: ByteBuf) {
                stream.writeAndFlush(msg.retain())
            }

            override fun send(payload: ByteArray): CompletableFuture<ByteArray> {
                throw UnsupportedOperationException("Responder doesn't initiate sends")
            }
        }

        private class InitiatorController(
            private val ready: CompletableFuture<Void>
        ) : ProtocolMessageHandler<ByteBuf>, SizeEchoController {
            private lateinit var stream: Stream
            private var pending = CompletableFuture<ByteArray>()
            private var expectedBytes = 0
            private var received = ByteArrayOutputStream()

            override fun onActivated(stream: Stream) {
                this.stream = stream
                ready.complete(null)
            }

            override fun onMessage(stream: Stream, msg: ByteBuf) {
                if (pending.isDone) return
                received.write(msg.toByteArray())
                if (received.size() >= expectedBytes) {
                    val data = received.toByteArray()
                    pending.complete(data.copyOf(expectedBytes))
                }
            }

            override fun send(payload: ByteArray): CompletableFuture<ByteArray> {
                pending = CompletableFuture()
                expectedBytes = payload.size
                received = ByteArrayOutputStream(expectedBytes)
                stream.writeAndFlush(payload.toByteBuf())
                return pending
            }
        }
    }

    class NodeLogger(val nodeIdentifier: String) : PacketProcessorVisitor<DatagramPacket> {
        var nodeTime: Duration = Duration.ZERO
        var onNextTaskDurationMutedAt: Duration = Duration.ZERO - 1.seconds

        override fun onAdvance(advanceDuration: Duration) {
            val oldNodeTime = nodeTime
            nodeTime += advanceDuration
            println("[$nodeIdentifier] [$nodeTime] advanced by $advanceDuration ($oldNodeTime -> $nodeTime)" )
        }

        override fun onNextTaskDuration(nextTaskDuration: Duration?) {
            if (nodeTime > onNextTaskDurationMutedAt) {
                println("[$nodeIdentifier] [$nodeTime] next task duration: $nextTaskDuration (at ${nodeTime + (nextTaskDuration ?: Duration.INFINITE)})")
                onNextTaskDurationMutedAt = nodeTime
            }
        }

        override fun onDeliverInbound(inboundPacket: DatagramPacket) {
            println("[$nodeIdentifier] [$nodeTime]   ==> received packet of size " +
                    "${inboundPacket.content().readableBytes()} " +
                    "hash ${packetContentHashHex(inboundPacket)} " +
                    "from ${inboundPacket.sender().hostString}" )
        }

        override fun onDeliverOutbound(outboundPacket: DatagramPacket) {
            println("[$nodeIdentifier] [$nodeTime] <==   sent packet of size " +
                    "${outboundPacket.content().readableBytes()} " +
                    "hash ${packetContentHashHex(outboundPacket)} " +
                    "to ${outboundPacket.recipient().hostString}" )
        }

        private fun packetContentHashHex(packet: DatagramPacket): String {
            val content = packet.content()
            val bytes = ByteArray(content.readableBytes())
            content.getBytes(content.readerIndex(), bytes)
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .take(4)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    class PacketStatsNodeVisitorFactory : io.libp2p.quicsim.sim.SimNodeVisitorFactory<DatagramPacket> {
        private val outboundPackets = AtomicLong()
        private val inboundPackets = AtomicLong()
        private val outboundBytes = AtomicLong()
        private val inboundBytes = AtomicLong()
        private val inFlightPackets = AtomicLong()
        private val inFlightBytes = AtomicLong()
        private val maxInFlightPackets = AtomicLong()
        private val maxInFlightBytes = AtomicLong()
        private val peakPauseTriggered = java.util.concurrent.atomic.AtomicBoolean()
        private val pauseAtInFlightBytes =
            System.getProperty("quicsim.profile.pauseAtInFlightBytes")?.toLongOrNull()

        override fun create(ip: String): PacketProcessorVisitor<DatagramPacket> =
            object : PacketProcessorVisitor<DatagramPacket> {
                override fun onDeliverOutbound(outboundPacket: DatagramPacket) {
                    val packetBytes = outboundPacket.content().readableBytes().toLong()
                    outboundPackets.incrementAndGet()
                    outboundBytes.addAndGet(packetBytes)
                    updateMax(maxInFlightPackets, inFlightPackets.incrementAndGet())
                    val newInFlightBytes = inFlightBytes.addAndGet(packetBytes)
                    updateMax(maxInFlightBytes, newInFlightBytes)
                    pauseAtPeakIfNeeded(newInFlightBytes)
                }

                override fun onDeliverInbound(inboundPacket: DatagramPacket) {
                    val packetBytes = inboundPacket.content().readableBytes().toLong()
                    inboundPackets.incrementAndGet()
                    inboundBytes.addAndGet(packetBytes)
                    inFlightPackets.addAndGet(-1)
                    inFlightBytes.addAndGet(-packetBytes)
                }
            }

        fun snapshot(): Snapshot =
            Snapshot(
                outboundPackets = outboundPackets.get(),
                inboundPackets = inboundPackets.get(),
                outboundBytes = outboundBytes.get(),
                inboundBytes = inboundBytes.get(),
                inFlightPackets = inFlightPackets.get(),
                inFlightBytes = inFlightBytes.get(),
                maxInFlightPackets = maxInFlightPackets.get(),
                maxInFlightBytes = maxInFlightBytes.get()
            )

        private fun updateMax(maxValue: AtomicLong, candidate: Long) {
            while (true) {
                val current = maxValue.get()
                if (candidate <= current || maxValue.compareAndSet(current, candidate)) {
                    return
                }
            }
        }

        private fun pauseAtPeakIfNeeded(inFlightBytes: Long) {
            val threshold = pauseAtInFlightBytes ?: return
            if (inFlightBytes >= threshold && peakPauseTriggered.compareAndSet(false, true)) {
                println("Packet stats profiling pause at inFlightBytes=$inFlightBytes")
                Thread.sleep(120_000)
            }
        }

        data class Snapshot(
            val outboundPackets: Long,
            val inboundPackets: Long,
            val outboundBytes: Long,
            val inboundBytes: Long,
            val inFlightPackets: Long,
            val inFlightBytes: Long,
            val maxInFlightPackets: Long,
            val maxInFlightBytes: Long
        )
    }
}
