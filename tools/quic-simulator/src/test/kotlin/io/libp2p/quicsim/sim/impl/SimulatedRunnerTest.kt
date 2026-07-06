package io.libp2p.quicsim.sim.impl

import io.libp2p.core.Host
import io.libp2p.core.ConnectionHandler
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
import io.libp2p.quicsim.udpnetwork.TestQDiscFactory
import io.libp2p.quicsim.udpnetwork.fifoUdpSimQueue
import io.libp2p.quicsim.udpnetwork.latencyThenBandwidthUdpSimQueue
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl2
import io.netty.buffer.AdaptiveByteBufAllocator
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.WrappedByteBuf
import io.netty.channel.socket.DatagramPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        val nodeCount = intProperty("quicsim.sendMessageFromNPublishers.nodeCount", 1000)
        val publishersCount = intProperty("quicsim.sendMessageFromNPublishers.publishersCount", nodeCount)
        val neighboursToConnect = intProperty("quicsim.sendMessageFromNPublishers.neighboursToConnect", 20)
        val messagesPerPublisher = intProperty("quicsim.sendMessageFromNPublishers.messagesPerPublisher", 1)
        val bandwidth = Bandwidth(5_000_000L)
        val halfLatency = 20.milliseconds
        val messageSizeBytes = intProperty("quicsim.sendMessageFromNPublishers.messageSizeBytes", 130)
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val packetStats = PacketStatsNodeVisitorFactory()
        val directBufferStats = DirectBufferStatsSampler()
        val globalAllocator = CountingByteBufAllocator(
            delegate = AdaptiveByteBufAllocator(),
            captureAllocationStacks = System.getProperty("quicsim.profile.globalAllocatorParanoid").toBoolean()
        )
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
                        messagesPerPublisher = messagesPerPublisher,
                        initialPublishDelay = 30.seconds,
                    ).also { nodePrograms += it }
            },
            udpNetwork = udpNetwork,
            maxSimulatedRunDuration = 10.minutes,
            latencyWindowParallelism = 20,
            nodeVisitorFactory = packetStats,
            quicAllocatorFactory = { globalAllocator }
        )

        directBufferStats.start()
        try {
            runner.run()
        } finally {
            directBufferStats.stop()
        }
        assertTrue(
            nodePrograms.all { it.completeFuture.isDone },
            "Expected all sample gossip node programs to complete in 1000-node scenario"
        )

//        println("Total packet count: " + udpNetworkLogging.packetsCount + ", bytes: " + udpNetworkLogging.throughputBytes)
        println(
            "Params: neighboursToConnect: $neighboursToConnect, " +
                "publishersCount: $publishersCount, messagesPerPublisher: $messagesPerPublisher"
        )
        println("Packet stats: ${packetStats.snapshot()}")
        println("Direct buffer stats: ${directBufferStats.snapshot()}")
        println("Global shared allocation stats: ${globalAllocator.snapshot()}")
        globalAllocator.unreleasedAllocationReport(limit = 10)?.let { report ->
            println("Global shared unreleased allocation report:")
            println(report)
        }
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
            maxSimulatedRunDuration = 100.seconds,
            latencyWindowParallelism = 8
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
        val dialerConnected = CompletableFuture<Unit>()
        val listenerConnected = CompletableFuture<Unit>()

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
                                val expectedRemotePeerId = networkContext.allNodes[1]!!.getPeerId()
                                networkContext.myHost.network
                                    .connect(networkContext.allNodes[1]!!)
                                    .thenApply { connection ->
                                        assertEquals(expectedRemotePeerId, connection.secureSession().remoteId)
                                        dialerConnected.complete(Unit)
                                        completeFuture.complete(Unit)
                                        Unit
                                    }
                            } else {
                                val expectedRemotePeerId = networkContext.allNodes[0]!!.getPeerId()
                                networkContext.myHost.addConnectionHandler(ConnectionHandler.create { connection ->
                                    if (connection.secureSession().remoteId == expectedRemotePeerId) {
                                        listenerConnected.complete(Unit)
                                        completeFuture.complete(Unit)
                                    }
                                })
                                completeFuture
                            }
                        }

                    }
            },
            udpNetwork = builder.build(),
            nodeVisitorFactory = { NodeLogger(it) },
            latencyWindowParallelism = 1
        )

        runner.run()
        assertTrue(dialerConnected.isDone, "Expected dialer to establish a QUIC connection")
        assertTrue(listenerConnected.isDone, "Expected listener to observe inbound QUIC connection")
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
        fun intProperty(name: String, defaultValue: Int): Int =
            System.getProperty(name)?.toIntOrNull() ?: defaultValue

        fun fifoQDiscFactory(bandwidth: Bandwidth): TestQDiscFactory = { latency, isFromEndpoint ->
            if (isFromEndpoint) {
                latencyThenBandwidthUdpSimQueue(
                    bandwidth = bandwidth,
                    latency = latency
                )
            } else {
                fifoUdpSimQueue(
                    bandwidth = bandwidth,
                    latency = latency
                )
            }
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

    class DirectBufferStatsSampler(
        private val samplePeriodMillis: Long = 10
    ) {
        private val directPool = bufferPool("direct")
        private val mappedPool = bufferPool("mapped")
        private val running = AtomicBoolean()
        private val maxDirectCount = AtomicLong()
        private val maxDirectMemoryUsed = AtomicLong()
        private val maxDirectTotalCapacity = AtomicLong()
        private val maxMappedMemoryUsed = AtomicLong()
        private var thread: Thread? = null

        fun start() {
            if (!running.compareAndSet(false, true)) return
            thread = Thread {
                while (running.get()) {
                    sample()
                    Thread.sleep(samplePeriodMillis)
                }
            }.also {
                it.isDaemon = true
                it.name = "direct-buffer-stats-sampler"
                it.start()
            }
        }

        fun stop() {
            running.set(false)
            thread?.join(1_000)
            sample()
        }

        fun snapshot(): Snapshot =
            Snapshot(
                directCount = directPool?.count ?: -1,
                directMemoryUsed = directPool?.memoryUsed ?: -1,
                directTotalCapacity = directPool?.totalCapacity ?: -1,
                mappedMemoryUsed = mappedPool?.memoryUsed ?: -1,
                maxDirectCount = maxDirectCount.get(),
                maxDirectMemoryUsed = maxDirectMemoryUsed.get(),
                maxDirectTotalCapacity = maxDirectTotalCapacity.get(),
                maxMappedMemoryUsed = maxMappedMemoryUsed.get()
            )

        private fun sample() {
            directPool?.let {
                updateMax(maxDirectCount, it.count)
                updateMax(maxDirectMemoryUsed, it.memoryUsed)
                updateMax(maxDirectTotalCapacity, it.totalCapacity)
            }
            mappedPool?.let {
                updateMax(maxMappedMemoryUsed, it.memoryUsed)
            }
        }

        private fun updateMax(maxValue: AtomicLong, candidate: Long) {
            while (true) {
                val current = maxValue.get()
                if (candidate <= current || maxValue.compareAndSet(current, candidate)) {
                    return
                }
            }
        }

        data class Snapshot(
            val directCount: Long,
            val directMemoryUsed: Long,
            val directTotalCapacity: Long,
            val mappedMemoryUsed: Long,
            val maxDirectCount: Long,
            val maxDirectMemoryUsed: Long,
            val maxDirectTotalCapacity: Long,
            val maxMappedMemoryUsed: Long
        )

        private fun bufferPool(name: String): BufferPoolMXBean? =
            ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
                .firstOrNull { it.name == name }
    }

    class CountingByteBufAllocator(
        private val delegate: ByteBufAllocator,
        private val captureAllocationStacks: Boolean = false
    ) : ByteBufAllocator {
        private data class AllocationRecord(
            val id: Long,
            val requestedBytes: Long,
            val initialCapacityBytes: Long,
            val direct: Boolean,
            val stack: Array<StackTraceElement>
        )

        private val allocationIds = AtomicLong()
        private val activeAllocationRecords = ConcurrentHashMap<Long, AllocationRecord>()
        private val allocatedBuffers = AtomicLong()
        private val releasedBuffers = AtomicLong()
        private val activeBuffers = AtomicLong()
        private val maxActiveBuffers = AtomicLong()
        private val allocatedRequestedBytes = AtomicLong()
        private val allocatedCapacityBytes = AtomicLong()
        private val allocatedDirectCapacityBytes = AtomicLong()
        private val activeRequestedBytes = AtomicLong()
        private val activeCapacityBytes = AtomicLong()
        private val activeDirectCapacityBytes = AtomicLong()
        private val maxActiveRequestedBytes = AtomicLong()
        private val maxActiveCapacityBytes = AtomicLong()
        private val maxActiveDirectCapacityBytes = AtomicLong()

        override fun buffer(): ByteBuf =
            track(delegate.buffer(), requestedBytes = 0)

        override fun buffer(initialCapacity: Int): ByteBuf =
            track(delegate.buffer(initialCapacity), initialCapacity)

        override fun buffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            track(delegate.buffer(initialCapacity, maxCapacity), initialCapacity)

        override fun ioBuffer(): ByteBuf =
            track(delegate.ioBuffer(), requestedBytes = 0)

        override fun ioBuffer(initialCapacity: Int): ByteBuf =
            track(delegate.ioBuffer(initialCapacity), initialCapacity)

        override fun ioBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            track(delegate.ioBuffer(initialCapacity, maxCapacity), initialCapacity)

        override fun heapBuffer(): ByteBuf =
            track(delegate.heapBuffer(), requestedBytes = 0)

        override fun heapBuffer(initialCapacity: Int): ByteBuf =
            track(delegate.heapBuffer(initialCapacity), initialCapacity)

        override fun heapBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            track(delegate.heapBuffer(initialCapacity, maxCapacity), initialCapacity)

        override fun directBuffer(): ByteBuf =
            track(delegate.directBuffer(), requestedBytes = 0)

        override fun directBuffer(initialCapacity: Int): ByteBuf =
            track(delegate.directBuffer(initialCapacity), initialCapacity)

        override fun directBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            track(delegate.directBuffer(initialCapacity, maxCapacity), initialCapacity)

        override fun compositeBuffer(): CompositeByteBuf =
            delegate.compositeBuffer()

        override fun compositeBuffer(maxNumComponents: Int): CompositeByteBuf =
            delegate.compositeBuffer(maxNumComponents)

        override fun compositeHeapBuffer(): CompositeByteBuf =
            delegate.compositeHeapBuffer()

        override fun compositeHeapBuffer(maxNumComponents: Int): CompositeByteBuf =
            delegate.compositeHeapBuffer(maxNumComponents)

        override fun compositeDirectBuffer(): CompositeByteBuf =
            delegate.compositeDirectBuffer()

        override fun compositeDirectBuffer(maxNumComponents: Int): CompositeByteBuf =
            delegate.compositeDirectBuffer(maxNumComponents)

        override fun isDirectBufferPooled(): Boolean =
            delegate.isDirectBufferPooled

        override fun calculateNewCapacity(minNewCapacity: Int, maxCapacity: Int): Int =
            delegate.calculateNewCapacity(minNewCapacity, maxCapacity)

        fun snapshot(): Snapshot =
            Snapshot(
                allocatedBuffers = allocatedBuffers.get(),
                releasedBuffers = releasedBuffers.get(),
                activeBuffers = activeBuffers.get(),
                maxActiveBuffers = maxActiveBuffers.get(),
                allocatedRequestedBytes = allocatedRequestedBytes.get(),
                allocatedCapacityBytes = allocatedCapacityBytes.get(),
                allocatedDirectCapacityBytes = allocatedDirectCapacityBytes.get(),
                activeRequestedBytes = activeRequestedBytes.get(),
                activeCapacityBytes = activeCapacityBytes.get(),
                activeDirectCapacityBytes = activeDirectCapacityBytes.get(),
                maxActiveRequestedBytes = maxActiveRequestedBytes.get(),
                maxActiveCapacityBytes = maxActiveCapacityBytes.get(),
                maxActiveDirectCapacityBytes = maxActiveDirectCapacityBytes.get()
            )

        fun unreleasedAllocationReport(limit: Int): String? {
            if (!captureAllocationStacks || activeAllocationRecords.isEmpty()) return null

            return buildString {
                appendLine("activeAllocationRecords=${activeAllocationRecords.size}")
                activeAllocationRecords.values
                    .sortedByDescending { it.initialCapacityBytes }
                    .take(limit)
                    .forEachIndexed { index, record ->
                        appendLine(
                            "#${index + 1} id=${record.id} requestedBytes=${record.requestedBytes} " +
                                "initialCapacityBytes=${record.initialCapacityBytes} direct=${record.direct}"
                        )
                        record.stack
                            .dropWhile { it.className == CountingByteBufAllocator::class.java.name ||
                                it.className == CountingByteBuf::class.java.name ||
                                it.className.startsWith("java.lang.Thread")
                            }
                            .take(24)
                            .forEach { appendLine("  at $it") }
                    }
            }
        }

        private fun track(buffer: ByteBuf, requestedBytes: Int): ByteBuf {
            val capacityBytes = buffer.capacity().toLong()
            val directCapacityBytes = if (buffer.isDirect) capacityBytes else 0L

            allocatedBuffers.incrementAndGet()
            allocatedRequestedBytes.addAndGet(requestedBytes.toLong())
            allocatedCapacityBytes.addAndGet(capacityBytes)
            allocatedDirectCapacityBytes.addAndGet(directCapacityBytes)
            updateMax(maxActiveBuffers, activeBuffers.incrementAndGet())
            updateMax(maxActiveRequestedBytes, activeRequestedBytes.addAndGet(requestedBytes.toLong()))
            updateMax(maxActiveCapacityBytes, activeCapacityBytes.addAndGet(capacityBytes))
            updateMax(maxActiveDirectCapacityBytes, activeDirectCapacityBytes.addAndGet(directCapacityBytes))

            val allocationId = allocationIds.incrementAndGet()
            if (captureAllocationStacks) {
                activeAllocationRecords[allocationId] = AllocationRecord(
                    id = allocationId,
                    requestedBytes = requestedBytes.toLong(),
                    initialCapacityBytes = capacityBytes,
                    direct = buffer.isDirect,
                    stack = Thread.currentThread().stackTrace
                )
            }

            return CountingByteBuf(allocationId, buffer, requestedBytes.toLong(), capacityBytes, directCapacityBytes)
        }

        private fun release(allocationId: Long, requestedBytes: Long, capacityBytes: Long, directCapacityBytes: Long) {
            activeAllocationRecords.remove(allocationId)
            releasedBuffers.incrementAndGet()
            activeBuffers.decrementAndGet()
            activeRequestedBytes.addAndGet(-requestedBytes)
            activeCapacityBytes.addAndGet(-capacityBytes)
            activeDirectCapacityBytes.addAndGet(-directCapacityBytes)
        }

        private fun adjustCapacity(deltaCapacityBytes: Long, deltaDirectCapacityBytes: Long) {
            updateMax(maxActiveCapacityBytes, activeCapacityBytes.addAndGet(deltaCapacityBytes))
            updateMax(maxActiveDirectCapacityBytes, activeDirectCapacityBytes.addAndGet(deltaDirectCapacityBytes))
        }

        private fun updateMax(maxValue: AtomicLong, candidate: Long) {
            while (true) {
                val current = maxValue.get()
                if (candidate <= current || maxValue.compareAndSet(current, candidate)) {
                    return
                }
            }
        }

        private inner class CountingByteBuf(
            private val allocationId: Long,
            buffer: ByteBuf,
            private val requestedBytes: Long,
            initialCapacityBytes: Long,
            initialDirectCapacityBytes: Long
        ) : WrappedByteBuf(buffer) {
            private val released = AtomicBoolean()
            private var trackedCapacityBytes = initialCapacityBytes
            private var trackedDirectCapacityBytes = initialDirectCapacityBytes

            override fun capacity(newCapacity: Int): ByteBuf {
                val beforeCapacity = capacity().toLong()
                val beforeDirectCapacity = if (isDirect) beforeCapacity else 0L
                val result = super.capacity(newCapacity)
                val afterCapacity = capacity().toLong()
                val afterDirectCapacity = if (isDirect) afterCapacity else 0L
                trackedCapacityBytes += afterCapacity - beforeCapacity
                trackedDirectCapacityBytes += afterDirectCapacity - beforeDirectCapacity
                adjustCapacity(afterCapacity - beforeCapacity, afterDirectCapacity - beforeDirectCapacity)
                return result
            }

            override fun release(): Boolean {
                val deallocated = super.release()
                if (deallocated && released.compareAndSet(false, true)) {
                    release(allocationId, requestedBytes, trackedCapacityBytes, trackedDirectCapacityBytes)
                }
                return deallocated
            }

            override fun release(decrement: Int): Boolean {
                val deallocated = super.release(decrement)
                if (deallocated && released.compareAndSet(false, true)) {
                    release(allocationId, requestedBytes, trackedCapacityBytes, trackedDirectCapacityBytes)
                }
                return deallocated
            }
        }

        data class Snapshot(
            val allocatedBuffers: Long,
            val releasedBuffers: Long,
            val activeBuffers: Long,
            val maxActiveBuffers: Long,
            val allocatedRequestedBytes: Long,
            val allocatedCapacityBytes: Long,
            val allocatedDirectCapacityBytes: Long,
            val activeRequestedBytes: Long,
            val activeCapacityBytes: Long,
            val activeDirectCapacityBytes: Long,
            val maxActiveRequestedBytes: Long,
            val maxActiveCapacityBytes: Long,
            val maxActiveDirectCapacityBytes: Long
        )
    }

}
