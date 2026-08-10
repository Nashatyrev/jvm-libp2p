package io.libp2p.quicsim.sim.impl

import com.google.protobuf.CodedOutputStream
import io.libp2p.core.Host
import io.libp2p.core.ConnectionHandler
import io.libp2p.core.Stream
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.etc.util.P2PServiceWriteStats
import io.libp2p.etc.util.netty.protobuf.ProtobufFrameDecoderStats
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.pubsub.gossip.GossipRpcFrameStats
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.quicsim.core.PacketProcessorVisitor
import io.libp2p.quicsim.core.schedule.impl.submitAfterDelay
import io.libp2p.quicsim.program.DataChunkMetrics
import io.libp2p.quicsim.program.DataChunkNodeProgramFactory
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.program.SampleGossipNodeProgram
import io.libp2p.quicsim.runner.IPManager
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
import io.netty.buffer.ByteBufAllocatorMetric
import io.netty.buffer.ByteBufAllocatorMetricProvider
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.buffer.WrappedByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.socket.DatagramPacket
import io.netty.util.IllegalReferenceCountException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import pubsub.pb.Rpc

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
    @Disabled("Manual performance benchmark")
    fun `advances one hour with 1000 idle nodes`() {
        val nodeCount = 1_000
        val networkBuilder = TestStarNetworkBuilder2()
        networkBuilder.addIpNodes(nodeCount)
        networkBuilder.linkAllToRouter(
            20.milliseconds,
            fifoQDiscFactory(Bandwidth(5_000_000L))
        )
        val runner = SimulatedRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: SimNodeId): NodeProgram = IdleNodeProgram(id)
            },
            udpNetwork = networkBuilder.build(),
            maxSimulatedRunDuration = 60.minutes,
            latencyWindowParallelism = 16,
            newNetworkController = true
        )

        runner.run()

        assertEquals(60.minutes, runner.simTimer.elapsedTime())
    }

    @Test
    fun `simulated runner completes 5-node ring with sample gossip`() {
        val nodeCount = 5
        val publisherCount = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val networkBuilder = TestStarNetworkBuilder2()
        networkBuilder.addIpNodes(nodeCount)
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
            udpNetwork = networkBuilder.build(),
            latencyWindowParallelism = 4,
            newNetworkController = true
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.completeFuture.isDone },
            "Expected all sample gossip node programs to complete"
        )
    }

    @Test
    fun sendMessageFromNPublishers() {
        val nodeCount = intProperty("quicsim.sendMessageFromNPublishers.nodeCount", 500)
        val publishersCount = intProperty("quicsim.sendMessageFromNPublishers.publishersCount", nodeCount)
        val neighboursToConnect = intProperty("quicsim.sendMessageFromNPublishers.neighboursToConnect", 20)
        val messagesPerPublisher = intProperty("quicsim.sendMessageFromNPublishers.messagesPerPublisher", 1)
        val initialPublishDelaySeconds =
            intProperty("quicsim.sendMessageFromNPublishers.initialPublishDelaySeconds", 10)
        val maxPublishedMessagesPerRpc =
            intProperty("quicsim.sendMessageFromNPublishers.maxPublishedMessagesPerRpc", 256)
        val maxGossipMessageSizeBytes =
            intProperty("quicsim.sendMessageFromNPublishers.maxGossipMessageSizeBytes", 1 shl 20)
        val bandwidth = Bandwidth(5_000_000L)
        val halfLatency = 20.milliseconds
        val messageSizeBytes = intProperty("quicsim.sendMessageFromNPublishers.messageSizeBytes", 130)
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val heapStats = HeapStatsSampler()
        val packetStats = PacketStatsNodeVisitorFactory(
            onRetainedHeapThreshold = {
                val retainedBytes = heapStats.sampleRetainedNow()
                println("Retained heap sample at packet in-flight threshold: retainedHeapUsedBytes=$retainedBytes")
            }
        )
        val directBufferStats = DirectBufferStatsSampler()
        val unpooledAllocatorStats = UnpooledAllocatorStatsSampler()
        val defaultAllocatorStats = DefaultAllocatorStatsSampler()
        val rssStats = RssStatsSampler()
        val allocatorMode = System.getProperty("quicsim.profile.allocator", "adaptive")
        val forceHeapByteBufs = System.getProperty("quicsim.profile.heapByteBufs").toBoolean()
        val useSharedAllocator = System.getProperty("quicsim.profile.sharedAllocator").toBoolean()
        val globalAllocator =
            if (useSharedAllocator) {
                CountingByteBufAllocator(
                    delegate = quicAllocatorDelegate(allocatorMode, forceHeapByteBufs),
                    captureAllocationStacks = System.getProperty("quicsim.profile.globalAllocatorParanoid").toBoolean()
                )
            } else {
                null
        }
        val profiledNodeId = System.getProperty("quicsim.nodeHeapProfile.nodeId")?.toIntOrNull()
        val gossipRpcNodeStatsNodeId =
            System.getProperty("quicsim.profile.gossipRpcNodeStatsNodeId")?.toIntOrNull()
        val gossipRpcNodeStats = gossipRpcNodeStatsNodeId?.let { GossipRpcNodeStats() }
        val gossipRpcNodeStatsHandler = gossipRpcNodeStats?.let { GossipRpcNodeStatsHandler(it) }
        val profiledAllocatorMode = System.getProperty("quicsim.nodeHeapProfile.allocator", "unpooled")
        val profiledAllocator = profiledNodeId?.let {
            CountingByteBufAllocator(
                delegate = quicAllocatorDelegate(profiledAllocatorMode, forceHeapByteBufs),
                captureAllocationStacks = System.getProperty("quicsim.profile.nodeAllocatorParanoid").toBoolean()
            )
        }
        val allocatorStats = CountingAllocatorStatsSampler(
            allocators = listOfNotNull(globalAllocator, profiledAllocator),
            samplePeriodMillis = System.getProperty("quicsim.profile.allocatorSamplePeriodMillis")
                ?.toLongOrNull() ?: 50L
        )
        val randomConnectionsByNode: Map<SimNodeId, List<SimNodeId>> =
            QuicScenarios.createBidirectionalRandomTopology(nodeCount, neighboursToConnect, seed = 1234)

        P2PServiceWriteStats.reset()
        ProtobufFrameDecoderStats.reset()
        GossipRpcFrameStats.reset()
        val networkBuilder = TestStarNetworkBuilder2()
        networkBuilder.addIpNodes(nodeCount)
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
                            maxPublishedMessages = maxPublishedMessagesPerRpc,
                            maxGossipMessageSize = maxGossipMessageSizeBytes,
                        ),
                        randomSeed = id.toLong(),
                        messageSizeBytes = messageSizeBytes,
                        messagesPerPublisher = messagesPerPublisher,
                        initialPublishDelay = initialPublishDelaySeconds.seconds,
                        debugGossipHandler = if (id == gossipRpcNodeStatsNodeId) gossipRpcNodeStatsHandler else null,
                    ).also { nodePrograms += it }
            },
            udpNetwork = udpNetwork,
            maxSimulatedRunDuration = 10.minutes,
            nodeVisitorFactory = packetStats,
            quicAllocatorFactory = { nodeId ->
                if (profiledAllocator != null && nodeId == profiledNodeId) {
                    profiledAllocator
                } else {
                    globalAllocator ?: quicAllocatorDelegate(allocatorMode, forceHeapByteBufs)
                }
            },
            latencyWindowParallelism = 16,
            newNetworkController = true
        )

        directBufferStats.start()
        unpooledAllocatorStats.start()
        defaultAllocatorStats.start()
        rssStats.start()
        heapStats.start()
        allocatorStats.start()
        try {
            runner.run()
        } finally {
            allocatorStats.stop()
            heapStats.stop()
            directBufferStats.stop()
            unpooledAllocatorStats.stop()
            defaultAllocatorStats.stop()
            rssStats.stop()
        }
        val allProgramsComplete = nodePrograms.all { it.completeFuture.isDone }

//        println("Total packet count: " + udpNetworkLogging.packetsCount + ", bytes: " + udpNetworkLogging.throughputBytes)
        println(
            "Params: neighboursToConnect: $neighboursToConnect, " +
                "publishersCount: $publishersCount, messagesPerPublisher: $messagesPerPublisher, " +
                "initialPublishDelaySeconds: $initialPublishDelaySeconds, " +
                "maxPublishedMessagesPerRpc: $maxPublishedMessagesPerRpc, " +
                "maxGossipMessageSizeBytes: $maxGossipMessageSizeBytes"
        )
        println("Allocator mode: ${if (forceHeapByteBufs) "heap" else allocatorMode}")
        println("Allocator scope: ${if (useSharedAllocator) "shared-counted" else "per-node"}")
        profiledNodeId?.let {
            println("Profiled node allocator: nodeId=$it mode=${if (forceHeapByteBufs) "heap" else profiledAllocatorMode}")
        }
        println("Packet stats: ${packetStats.snapshot()}")
        println("Direct buffer stats: ${directBufferStats.snapshot()}")
        println("Unpooled allocator stats: ${unpooledAllocatorStats.snapshot()}")
        println("Default allocator stats: ${defaultAllocatorStats.snapshot()}")
        println("P2P write stats: ${P2PServiceWriteStats.snapshot()}")
        println("Protobuf frame decoder stats: ${ProtobufFrameDecoderStats.snapshot()}")
        println("Gossip RPC frame stats: ${GossipRpcFrameStats.snapshot()}")
        gossipRpcNodeStats?.let {
            println("Gossip RPC node stats: nodeId=$gossipRpcNodeStatsNodeId ${it.snapshot()}")
        }
        println("RSS stats: ${rssStats.snapshot()}")
        println("Heap stats: ${heapStats.snapshot()}")
        globalAllocator?.let { allocator ->
            println("Global shared allocation stats: ${allocator.snapshot()}")
        }
        profiledAllocator?.let { allocator ->
            println("Profiled node allocation stats: nodeId=$profiledNodeId ${allocator.snapshot()}")
            allocator.activeAllocationSummaryReport(limit = 20)?.let { report ->
                println("Profiled node active allocation summary:")
                println(report)
            }
            allocator.peakActiveDirectAllocationReport()?.let { report ->
                println("Profiled node peak active direct allocation report:")
                println(report)
            }
            allocator.unreleasedAllocationReport(limit = 10)?.let { report ->
                println("Profiled node unreleased allocation report:")
                println(report)
            }
        }
        globalAllocator?.let { allocator ->
            allocator.unreleasedAllocationReport(limit = 10)?.let { report ->
                println("Global shared unreleased allocation report:")
                println(report)
            }
        }
        if (System.getProperty("quicsim.profile.stopAtCheckpoint").toBoolean()) {
            println("Checkpoint diagnostic run stopped before completion; complete=$allProgramsComplete")
        } else {
            assertTrue(
                allProgramsComplete,
                "Expected all sample gossip node programs to complete in 1000-node scenario"
            )
        }
    }

    private fun quicAllocatorDelegate(allocatorMode: String, forceHeapByteBufs: Boolean): ByteBufAllocator =
        when {
            forceHeapByteBufs -> HeapOnlyByteBufAllocator(AdaptiveByteBufAllocator())
            allocatorMode == "adaptive" -> AdaptiveByteBufAllocator()
            allocatorMode == "unpooled" -> UnpooledByteBufAllocator(true)
            else -> error("Unsupported quicsim.profile.allocator=$allocatorMode")
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
        builder.addIpNodes(nodeCount)
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
        builder.addIpNodes(nodeCount)
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
        builder.addIpNodes(2)
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
            latencyWindowParallelism = 4,
            newNetworkController = true
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
        builder.addIpNodes(2)
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

    private class IdleNodeProgram(
        override val simNodeId: SimNodeId,
    ) : NodeProgram {
        override val completeFuture: CompletableFuture<Unit> = CompletableFuture()

        override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> = emptyList()

        override fun start(
            simContext: SimContext,
            networkContext: NetworkContext,
        ): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
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

    class PacketStatsNodeVisitorFactory(
        private val onRetainedHeapThreshold: (() -> Unit)? = null
    ) : io.libp2p.quicsim.sim.SimNodeVisitorFactory<DatagramPacket> {
        private val outboundPackets = AtomicLong()
        private val inboundPackets = AtomicLong()
        private val outboundBytes = AtomicLong()
        private val inboundBytes = AtomicLong()
        private val inFlightPackets = AtomicLong()
        private val inFlightBytes = AtomicLong()
        private val maxInFlightPackets = AtomicLong()
        private val maxInFlightBytes = AtomicLong()
        private val peakPauseTriggered = java.util.concurrent.atomic.AtomicBoolean()
        private val retainedHeapThresholdTriggered = java.util.concurrent.atomic.AtomicBoolean()
        private val pauseAtInFlightBytes =
            System.getProperty("quicsim.profile.pauseAtInFlightBytes")?.toLongOrNull()
        private val retainedHeapAtInFlightBytes =
            System.getProperty("quicsim.profile.retainedHeapAtInFlightBytes")?.toLongOrNull()

        override fun create(ip: String): PacketProcessorVisitor<DatagramPacket> =
            object : PacketProcessorVisitor<DatagramPacket> {
                override fun onDeliverOutbound(outboundPacket: DatagramPacket) {
                    val packetBytes = outboundPacket.content().readableBytes().toLong()
                    outboundPackets.incrementAndGet()
                    outboundBytes.addAndGet(packetBytes)
                    updateMax(maxInFlightPackets, inFlightPackets.incrementAndGet())
                    val newInFlightBytes = inFlightBytes.addAndGet(packetBytes)
                    updateMax(maxInFlightBytes, newInFlightBytes)
                    sampleRetainedHeapAtPeakIfNeeded(newInFlightBytes)
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

        private fun sampleRetainedHeapAtPeakIfNeeded(inFlightBytes: Long) {
            val threshold = retainedHeapAtInFlightBytes ?: return
            if (inFlightBytes >= threshold && retainedHeapThresholdTriggered.compareAndSet(false, true)) {
                println("Packet stats retained heap threshold reached at inFlightBytes=$inFlightBytes")
                onRetainedHeapThreshold?.invoke()
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

    class UnpooledAllocatorStatsSampler(
        private val samplePeriodMillis: Long = 10
    ) {
        private val metric = UnpooledByteBufAllocator.DEFAULT.metric()
        private val running = AtomicBoolean()
        private val maxUsedHeapMemory = AtomicLong()
        private val maxUsedDirectMemory = AtomicLong()
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
                it.name = "unpooled-allocator-stats-sampler"
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
                usedHeapMemory = metric.usedHeapMemory(),
                usedDirectMemory = metric.usedDirectMemory(),
                maxUsedHeapMemory = maxUsedHeapMemory.get(),
                maxUsedDirectMemory = maxUsedDirectMemory.get()
            )

        private fun sample() {
            updateMax(maxUsedHeapMemory, metric.usedHeapMemory())
            updateMax(maxUsedDirectMemory, metric.usedDirectMemory())
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
            val usedHeapMemory: Long,
            val usedDirectMemory: Long,
            val maxUsedHeapMemory: Long,
            val maxUsedDirectMemory: Long
        )
    }

    class DefaultAllocatorStatsSampler(
        private val samplePeriodMillis: Long = 10
    ) {
        private val metric = (ByteBufAllocator.DEFAULT as? ByteBufAllocatorMetricProvider)?.metric()
        private val running = AtomicBoolean()
        private val maxUsedHeapMemory = AtomicLong()
        private val maxUsedDirectMemory = AtomicLong()
        private var thread: Thread? = null

        fun start() {
            if (metric == null || !running.compareAndSet(false, true)) return
            thread = Thread {
                while (running.get()) {
                    sample()
                    Thread.sleep(samplePeriodMillis)
                }
            }.also {
                it.isDaemon = true
                it.name = "default-allocator-stats-sampler"
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
                usedHeapMemory = metric?.usedHeapMemory() ?: -1,
                usedDirectMemory = metric?.usedDirectMemory() ?: -1,
                maxUsedHeapMemory = maxUsedHeapMemory.get(),
                maxUsedDirectMemory = maxUsedDirectMemory.get()
            )

        private fun sample() {
            metric?.let {
                updateMax(maxUsedHeapMemory, it.usedHeapMemory())
                updateMax(maxUsedDirectMemory, it.usedDirectMemory())
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
            val usedHeapMemory: Long,
            val usedDirectMemory: Long,
            val maxUsedHeapMemory: Long,
            val maxUsedDirectMemory: Long
        )
    }

    class RssStatsSampler(
        private val samplePeriodMillis: Long =
            System.getProperty("quicsim.profile.rssSamplePeriodMillis")?.toLongOrNull() ?: 100
    ) {
        private val pid = ProcessHandle.current().pid()
        private val running = AtomicBoolean()
        private val maxRssBytes = AtomicLong()
        private val lastRssBytes = AtomicLong(-1)
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
                it.name = "rss-stats-sampler"
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
                pid = pid,
                rssBytes = lastRssBytes.get(),
                maxRssBytes = maxRssBytes.get()
            )

        private fun sample() {
            val rssBytes = readRssBytes() ?: return
            lastRssBytes.set(rssBytes)
            updateMax(maxRssBytes, rssBytes)
        }

        private fun readRssBytes(): Long? =
            runCatching {
                val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText().trim()
                if (process.waitFor() != 0 || output.isBlank()) {
                    null
                } else {
                    output.lineSequence().first().trim().toLongOrNull()?.times(1024)
                }
            }.getOrNull()

        private fun updateMax(maxValue: AtomicLong, candidate: Long) {
            while (true) {
                val current = maxValue.get()
                if (candidate <= current || maxValue.compareAndSet(current, candidate)) {
                    return
                }
            }
        }

        data class Snapshot(
            val pid: Long,
            val rssBytes: Long,
            val maxRssBytes: Long
        )
    }

    class HeapStatsSampler(
        private val samplePeriodMillis: Long =
            System.getProperty("quicsim.profile.heapSamplePeriodMillis")?.toLongOrNull() ?: 10,
        private val retainedSamplePeriodMillis: Long? =
            System.getProperty("quicsim.profile.heapRetainedSamplePeriodMillis")?.toLongOrNull(),
        private val retainedGcSettleMillis: Long =
            System.getProperty("quicsim.profile.heapRetainedGcSettleMillis")?.toLongOrNull() ?: 20
    ) {
        private val memoryBean = ManagementFactory.getMemoryMXBean()
        private val running = AtomicBoolean()
        private val maxHeapUsedBytes = AtomicLong()
        private val maxHeapCommittedBytes = AtomicLong()
        private val maxRetainedHeapUsedBytes = AtomicLong(-1)
        private val retainedSamples = AtomicLong()
        private val lastHeapUsedBytes = AtomicLong(-1)
        private val lastHeapCommittedBytes = AtomicLong(-1)
        private val lastRetainedHeapUsedBytes = AtomicLong(-1)
        private var lastRetainedSampleNanos = Long.MIN_VALUE
        private var thread: Thread? = null

        fun start() {
            if (!running.compareAndSet(false, true)) return
            thread = Thread {
                while (running.get()) {
                    sample(forceRetained = false)
                    Thread.sleep(samplePeriodMillis)
                }
            }.also {
                it.isDaemon = true
                it.name = "heap-stats-sampler"
                it.start()
            }
        }

        fun stop() {
            running.set(false)
            thread?.join(1_000)
            sample(forceRetained = true)
        }

        fun snapshot(): Snapshot =
            Snapshot(
                heapUsedBytes = lastHeapUsedBytes.get(),
                heapCommittedBytes = lastHeapCommittedBytes.get(),
                maxHeapUsedBytes = maxHeapUsedBytes.get(),
                maxHeapCommittedBytes = maxHeapCommittedBytes.get(),
                retainedHeapUsedBytes = lastRetainedHeapUsedBytes.get(),
                maxRetainedHeapUsedBytes = maxRetainedHeapUsedBytes.get(),
                retainedSamples = retainedSamples.get()
            )

        fun sampleRetainedNow(): Long =
            sampleRetained()

        private fun sample(forceRetained: Boolean) {
            val heapUsage = memoryBean.heapMemoryUsage
            lastHeapUsedBytes.set(heapUsage.used)
            lastHeapCommittedBytes.set(heapUsage.committed)
            updateMax(maxHeapUsedBytes, heapUsage.used)
            updateMax(maxHeapCommittedBytes, heapUsage.committed)
            if (forceRetained || shouldSampleRetained()) {
                sampleRetained()
            }
        }

        private fun shouldSampleRetained(): Boolean {
            val periodMillis = retainedSamplePeriodMillis ?: return false
            val now = System.nanoTime()
            val periodNanos = TimeUnit.MILLISECONDS.toNanos(periodMillis)
            if (lastRetainedSampleNanos != Long.MIN_VALUE && now < lastRetainedSampleNanos + periodNanos) {
                return false
            }
            lastRetainedSampleNanos = now
            return true
        }

        @Synchronized
        private fun sampleRetained(): Long {
            System.gc()
            if (retainedGcSettleMillis > 0) {
                Thread.sleep(retainedGcSettleMillis)
            }
            val retainedBytes = memoryBean.heapMemoryUsage.used
            lastRetainedHeapUsedBytes.set(retainedBytes)
            updateMax(maxRetainedHeapUsedBytes, retainedBytes)
            retainedSamples.incrementAndGet()
            return retainedBytes
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
            val heapUsedBytes: Long,
            val heapCommittedBytes: Long,
            val maxHeapUsedBytes: Long,
            val maxHeapCommittedBytes: Long,
            val retainedHeapUsedBytes: Long,
            val maxRetainedHeapUsedBytes: Long,
            val retainedSamples: Long
        )
    }

    class CountingAllocatorStatsSampler(
        private val allocators: List<CountingByteBufAllocator>,
        private val samplePeriodMillis: Long
    ) {
        private val running = AtomicBoolean()
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
                it.name = "counting-allocator-stats-sampler"
                it.start()
            }
        }

        fun stop() {
            running.set(false)
            thread?.join(1_000)
            sample()
        }

        private fun sample() {
            allocators.forEach { it.sampleTrackedCapacitiesAndDelegateMetric() }
        }
    }

    class GossipRpcNodeStats {
        private val inbound = DirectionStats()
        private val outbound = DirectionStats()

        fun recordInbound(rpc: Rpc.RPC) {
            inbound.record(rpc)
        }

        fun recordOutbound(rpc: Rpc.RPC) {
            outbound.record(rpc)
        }

        fun snapshot(): Snapshot =
            Snapshot(
                inbound = inbound.snapshot(),
                outbound = outbound.snapshot()
            )

        data class Snapshot(
            val inbound: DirectionSnapshot,
            val outbound: DirectionSnapshot
        )

        data class DirectionSnapshot(
            val rpcFrames: Long,
            val rpcBytes: Long,
            val publishFrames: Long,
            val publishMessages: Long,
            val publishBytes: Long,
            val controlFrames: Long,
            val controlSubmessages: Long,
            val controlBytes: Long,
            val iHaveMessages: Long,
            val iHaveMessageIds: Long,
            val iHaveBytes: Long,
            val iWantMessages: Long,
            val iWantMessageIds: Long,
            val iWantBytes: Long,
            val graftMessages: Long,
            val graftBytes: Long,
            val pruneMessages: Long,
            val prunePeers: Long,
            val pruneBytes: Long,
            val iDontWantMessages: Long,
            val iDontWantMessageIds: Long,
            val iDontWantBytes: Long
        )

        private class DirectionStats {
            private val rpcFrames = AtomicLong()
            private val rpcBytes = AtomicLong()
            private val publishFrames = AtomicLong()
            private val publishMessages = AtomicLong()
            private val publishBytes = AtomicLong()
            private val controlFrames = AtomicLong()
            private val controlSubmessages = AtomicLong()
            private val controlBytes = AtomicLong()
            private val iHaveMessages = AtomicLong()
            private val iHaveMessageIds = AtomicLong()
            private val iHaveBytes = AtomicLong()
            private val iWantMessages = AtomicLong()
            private val iWantMessageIds = AtomicLong()
            private val iWantBytes = AtomicLong()
            private val graftMessages = AtomicLong()
            private val graftBytes = AtomicLong()
            private val pruneMessages = AtomicLong()
            private val prunePeers = AtomicLong()
            private val pruneBytes = AtomicLong()
            private val iDontWantMessages = AtomicLong()
            private val iDontWantMessageIds = AtomicLong()
            private val iDontWantBytes = AtomicLong()

            fun record(rpc: Rpc.RPC) {
                rpcFrames.incrementAndGet()
                rpcBytes.addAndGet(rpc.serializedSize.toLong())

                if (rpc.publishCount > 0) {
                    publishFrames.incrementAndGet()
                    publishMessages.addAndGet(rpc.publishCount.toLong())
                    publishBytes.addAndGet(
                        rpc.publishList.sumOf {
                            fieldSize(Rpc.RPC.PUBLISH_FIELD_NUMBER, it.serializedSize)
                        }.toLong()
                    )
                }

                if (rpc.hasControl()) {
                    val control = rpc.control
                    val iHaveBytesValue = control.ihaveList.sumOf {
                        fieldSize(Rpc.ControlMessage.IHAVE_FIELD_NUMBER, it.serializedSize)
                    }
                    val iWantBytesValue = control.iwantList.sumOf {
                        fieldSize(Rpc.ControlMessage.IWANT_FIELD_NUMBER, it.serializedSize)
                    }
                    val graftBytesValue = control.graftList.sumOf {
                        fieldSize(Rpc.ControlMessage.GRAFT_FIELD_NUMBER, it.serializedSize)
                    }
                    val pruneBytesValue = control.pruneList.sumOf {
                        fieldSize(Rpc.ControlMessage.PRUNE_FIELD_NUMBER, it.serializedSize)
                    }
                    val iDontWantBytesValue = control.idontwantList.sumOf {
                        fieldSize(Rpc.ControlMessage.IDONTWANT_FIELD_NUMBER, it.serializedSize)
                    }

                    controlFrames.incrementAndGet()
                    controlSubmessages.addAndGet(
                        (control.ihaveCount + control.iwantCount + control.graftCount +
                            control.pruneCount + control.idontwantCount).toLong()
                    )
                    controlBytes.addAndGet(
                        fieldSize(Rpc.RPC.CONTROL_FIELD_NUMBER, control.serializedSize).toLong()
                    )
                    iHaveMessages.addAndGet(control.ihaveCount.toLong())
                    iHaveMessageIds.addAndGet(control.ihaveList.sumOf { it.messageIDsCount }.toLong())
                    iHaveBytes.addAndGet(iHaveBytesValue.toLong())
                    iWantMessages.addAndGet(control.iwantCount.toLong())
                    iWantMessageIds.addAndGet(control.iwantList.sumOf { it.messageIDsCount }.toLong())
                    iWantBytes.addAndGet(iWantBytesValue.toLong())
                    graftMessages.addAndGet(control.graftCount.toLong())
                    graftBytes.addAndGet(graftBytesValue.toLong())
                    pruneMessages.addAndGet(control.pruneCount.toLong())
                    prunePeers.addAndGet(control.pruneList.sumOf { it.peersCount }.toLong())
                    pruneBytes.addAndGet(pruneBytesValue.toLong())
                    iDontWantMessages.addAndGet(control.idontwantCount.toLong())
                    iDontWantMessageIds.addAndGet(control.idontwantList.sumOf { it.messageIDsCount }.toLong())
                    iDontWantBytes.addAndGet(iDontWantBytesValue.toLong())
                }
            }

            private companion object {
                fun fieldSize(fieldNumber: Int, messageSize: Int): Int =
                    CodedOutputStream.computeTagSize(fieldNumber) +
                        CodedOutputStream.computeUInt32SizeNoTag(messageSize) +
                        messageSize
            }

            fun snapshot(): DirectionSnapshot =
                DirectionSnapshot(
                    rpcFrames = rpcFrames.get(),
                    rpcBytes = rpcBytes.get(),
                    publishFrames = publishFrames.get(),
                    publishMessages = publishMessages.get(),
                    publishBytes = publishBytes.get(),
                    controlFrames = controlFrames.get(),
                    controlSubmessages = controlSubmessages.get(),
                    controlBytes = controlBytes.get(),
                    iHaveMessages = iHaveMessages.get(),
                    iHaveMessageIds = iHaveMessageIds.get(),
                    iHaveBytes = iHaveBytes.get(),
                    iWantMessages = iWantMessages.get(),
                    iWantMessageIds = iWantMessageIds.get(),
                    iWantBytes = iWantBytes.get(),
                    graftMessages = graftMessages.get(),
                    graftBytes = graftBytes.get(),
                    pruneMessages = pruneMessages.get(),
                    prunePeers = prunePeers.get(),
                    pruneBytes = pruneBytes.get(),
                    iDontWantMessages = iDontWantMessages.get(),
                    iDontWantMessageIds = iDontWantMessageIds.get(),
                    iDontWantBytes = iDontWantBytes.get()
                )
        }
    }

    @ChannelHandler.Sharable
    class GossipRpcNodeStatsHandler(
        private val stats: GossipRpcNodeStats
    ) : ChannelDuplexHandler() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is Rpc.RPC) {
                stats.recordInbound(msg)
            }
            super.channelRead(ctx, msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is Rpc.RPC) {
                stats.recordOutbound(msg)
            }
            super.write(ctx, msg, promise)
        }
    }

    class HeapOnlyByteBufAllocator(
        private val delegate: ByteBufAllocator
    ) : ByteBufAllocator, ByteBufAllocatorMetricProvider {
        override fun buffer(): ByteBuf =
            delegate.heapBuffer()

        override fun buffer(initialCapacity: Int): ByteBuf =
            delegate.heapBuffer(initialCapacity)

        override fun buffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            delegate.heapBuffer(initialCapacity, maxCapacity)

        override fun ioBuffer(): ByteBuf =
            delegate.heapBuffer()

        override fun ioBuffer(initialCapacity: Int): ByteBuf =
            delegate.heapBuffer(initialCapacity)

        override fun ioBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            delegate.heapBuffer(initialCapacity, maxCapacity)

        override fun heapBuffer(): ByteBuf =
            delegate.heapBuffer()

        override fun heapBuffer(initialCapacity: Int): ByteBuf =
            delegate.heapBuffer(initialCapacity)

        override fun heapBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            delegate.heapBuffer(initialCapacity, maxCapacity)

        override fun directBuffer(): ByteBuf =
            delegate.heapBuffer()

        override fun directBuffer(initialCapacity: Int): ByteBuf =
            delegate.heapBuffer(initialCapacity)

        override fun directBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf =
            delegate.heapBuffer(initialCapacity, maxCapacity)

        override fun compositeBuffer(): CompositeByteBuf =
            delegate.compositeHeapBuffer()

        override fun compositeBuffer(maxNumComponents: Int): CompositeByteBuf =
            delegate.compositeHeapBuffer(maxNumComponents)

        override fun compositeHeapBuffer(): CompositeByteBuf =
            delegate.compositeHeapBuffer()

        override fun compositeHeapBuffer(maxNumComponents: Int): CompositeByteBuf =
            delegate.compositeHeapBuffer(maxNumComponents)

        override fun compositeDirectBuffer(): CompositeByteBuf =
            delegate.compositeHeapBuffer()

        override fun compositeDirectBuffer(maxNumComponents: Int): CompositeByteBuf =
            delegate.compositeHeapBuffer(maxNumComponents)

        override fun isDirectBufferPooled(): Boolean =
            false

        override fun calculateNewCapacity(minNewCapacity: Int, maxCapacity: Int): Int =
            delegate.calculateNewCapacity(minNewCapacity, maxCapacity)

        override fun metric(): ByteBufAllocatorMetric =
            (delegate as? ByteBufAllocatorMetricProvider)?.metric()
                ?: object : ByteBufAllocatorMetric {
                    override fun usedHeapMemory(): Long = -1
                    override fun usedDirectMemory(): Long = -1
                }
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

        private class ActiveAllocationState(
            val id: Long,
            val requestedBytes: Long,
            val buffer: ByteBuf,
            initialCapacityBytes: Long,
            initialDirectCapacityBytes: Long
        ) {
            var trackedCapacityBytes: Long = initialCapacityBytes
            var trackedDirectCapacityBytes: Long = initialDirectCapacityBytes

            fun bufferRefCnt(): Int =
                runCatching { buffer.refCnt() }.getOrDefault(-1)

            fun bufferCapacity(): Int =
                runCatching { buffer.capacity() }.getOrDefault(-1)

            fun bufferReadableBytes(): Int =
                runCatching { buffer.readableBytes() }.getOrDefault(-1)
        }

        private val allocationIds = AtomicLong()
        private val activeAllocationStates = ConcurrentHashMap<Long, ActiveAllocationState>()
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
        private val maxDelegateUsedHeapMemory = AtomicLong()
        private val maxDelegateUsedDirectMemory = AtomicLong()
        private val maxDelegateUntrackedOrPooledDirectMemory = AtomicLong()
        private val activeDirectAtMaxDelegateUsedDirectMemory = AtomicLong()
        private val untrackedOrPooledDirectAtMaxDelegateUsedDirectMemory = AtomicLong()
        private val delegateMetricSampleLock = Any()
        private val peakActiveDirectReportBytes = AtomicLong()
        private val peakActiveDirectReport = AtomicReference<String?>()

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

        fun snapshot(): Snapshot {
            refreshActiveCapacities(sampleDelegate = false)
            sampleDelegateMetric()
            return Snapshot(
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
                maxActiveDirectCapacityBytes = maxActiveDirectCapacityBytes.get(),
                delegateUsedHeapMemory = delegateMetric()?.usedHeapMemory() ?: -1,
                delegateUsedDirectMemory = delegateMetric()?.usedDirectMemory() ?: -1,
                delegateUntrackedOrPooledDirectMemory = delegateUntrackedOrPooledDirectMemory(),
                maxDelegateUsedHeapMemory = maxDelegateUsedHeapMemory.get(),
                maxDelegateUsedDirectMemory = maxDelegateUsedDirectMemory.get(),
                maxDelegateUntrackedOrPooledDirectMemory = maxDelegateUntrackedOrPooledDirectMemory.get(),
                activeDirectAtMaxDelegateUsedDirectMemory = activeDirectAtMaxDelegateUsedDirectMemory.get(),
                untrackedOrPooledDirectAtMaxDelegateUsedDirectMemory =
                    untrackedOrPooledDirectAtMaxDelegateUsedDirectMemory.get()
            )
        }

        fun sampleTrackedCapacitiesAndDelegateMetric() {
            refreshActiveCapacities(sampleDelegate = false)
            sampleDelegateMetric()
        }

        fun unreleasedAllocationReport(limit: Int): String? {
            if (!captureAllocationStacks || activeAllocationRecords.isEmpty()) return null

            return buildString {
                appendLine("activeAllocationRecords=${activeAllocationRecords.size}")
                activeAllocationRecords.values
                    .sortedByDescending { it.initialCapacityBytes }
                    .take(limit)
                    .forEachIndexed { index, record ->
                        val state = activeAllocationStates[record.id]
                        appendLine(
                            "#${index + 1} id=${record.id} requestedBytes=${record.requestedBytes} " +
                                "initialCapacityBytes=${record.initialCapacityBytes} direct=${record.direct} " +
                                "refCnt=${state?.bufferRefCnt() ?: -1} " +
                                "capacity=${state?.bufferCapacity() ?: -1} " +
                                "readableBytes=${state?.bufferReadableBytes() ?: -1}"
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

        fun activeAllocationSummaryReport(limit: Int): String? {
            if (!captureAllocationStacks || activeAllocationRecords.isEmpty()) return null

            refreshActiveCapacities(sampleDelegate = false)
            return activeAllocationSummaryReport(limit, refreshCapacities = false)
        }

        private fun activeAllocationSummaryReport(limit: Int, refreshCapacities: Boolean): String? {
            if (!captureAllocationStacks || activeAllocationRecords.isEmpty()) return null

            if (refreshCapacities) {
                refreshActiveCapacities(sampleDelegate = false)
            }
            val summaries = mutableMapOf<String, AllocationSummary>()
            activeAllocationRecords.values.forEach { record ->
                val state = activeAllocationStates[record.id] ?: return@forEach
                val label = classifyAllocation(record.stack)
                val summary = summaries.getOrPut(label) { AllocationSummary(label) }
                summary.count++
                summary.requestedBytes += record.requestedBytes
                summary.initialCapacityBytes += record.initialCapacityBytes
                val capacity = synchronized(state) { state.trackedCapacityBytes }
                val readableBytes = state.bufferReadableBytes().coerceAtLeast(0)
                summary.currentCapacityBytes += capacity
                summary.currentReadableBytes += readableBytes.toLong()
                if (record.direct) {
                    summary.directCapacityBytes += capacity
                }
            }

            return buildString {
                appendLine("activeAllocationRecords=${activeAllocationRecords.size}")
                summaries.values
                    .sortedWith(compareByDescending<AllocationSummary> { it.currentCapacityBytes }
                        .thenByDescending { it.count })
                    .take(limit)
                    .forEachIndexed { index, summary ->
                        appendLine(
                            "#${index + 1} ${summary.label} " +
                                "count=${summary.count} " +
                                "requestedBytes=${summary.requestedBytes} " +
                                "initialCapacityBytes=${summary.initialCapacityBytes} " +
                                "currentCapacityBytes=${summary.currentCapacityBytes} " +
                                "directCapacityBytes=${summary.directCapacityBytes} " +
                                "readableBytes=${summary.currentReadableBytes}"
                        )
                    }
            }
        }

        fun peakActiveDirectAllocationReport(): String? =
            peakActiveDirectReport.get()

        private data class AllocationSummary(
            val label: String,
            var count: Long = 0,
            var requestedBytes: Long = 0,
            var initialCapacityBytes: Long = 0,
            var currentCapacityBytes: Long = 0,
            var directCapacityBytes: Long = 0,
            var currentReadableBytes: Long = 0
        )

        private fun classifyAllocation(stack: Array<StackTraceElement>): String {
            fun contains(classNamePart: String, methodNamePart: String? = null): Boolean =
                stack.any { frame ->
                    frame.className.contains(classNamePart) &&
                        (methodNamePart == null || frame.methodName.contains(methodNamePart))
                }

            return when {
                contains("QuicheQuicStreamChannel", "recv") ->
                    "QUIC stream recv"
                contains("QuicheQuicChannel", "connectionSend") ->
                    "QUIC connection send"
                contains("P2PService") ->
                    "P2PService write path"
                contains("ProtobufEncoder") || contains("ProtobufVarint32LengthFieldPrepender") ->
                    "protobuf outbound encode"
                contains("LimitedProtobufVarint32FrameDecoder") || contains("ProtobufDecoder") ->
                    "protobuf inbound decode"
                contains("UdpSimNetworkEngine") || contains("SimPacketBridge") ->
                    "sim UDP bridge/network"
                else -> stack
                    .dropWhile { it.className == CountingByteBufAllocator::class.java.name ||
                        it.className == CountingByteBuf::class.java.name ||
                        it.className.startsWith("java.lang.Thread")
                    }
                    .firstOrNull()
                    ?.let { "${it.className}.${it.methodName}" }
                    ?: "unknown"
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
            val newActiveDirectCapacityBytes = activeDirectCapacityBytes.addAndGet(directCapacityBytes)
            updateMax(maxActiveDirectCapacityBytes, newActiveDirectCapacityBytes)
            sampleDelegateMetric()

            val allocationId = allocationIds.incrementAndGet()
            val activeState = ActiveAllocationState(
                id = allocationId,
                requestedBytes = requestedBytes.toLong(),
                buffer = buffer,
                initialCapacityBytes = capacityBytes,
                initialDirectCapacityBytes = directCapacityBytes
            )
            activeAllocationStates[allocationId] = activeState
            if (captureAllocationStacks) {
                activeAllocationRecords[allocationId] = AllocationRecord(
                    id = allocationId,
                    requestedBytes = requestedBytes.toLong(),
                    initialCapacityBytes = capacityBytes,
                    direct = buffer.isDirect,
                    stack = Thread.currentThread().stackTrace
                )
                maybeCapturePeakActiveDirectReport(newActiveDirectCapacityBytes)
            }

            return CountingByteBuf(allocationId, buffer, activeState)
        }

        private fun release(allocationId: Long, state: ActiveAllocationState) {
            refreshActiveCapacity(state, sampleDelegate = false)
            markReleased(allocationId, state)
        }

        private fun markReleased(allocationId: Long, state: ActiveAllocationState) {
            if (activeAllocationStates.remove(allocationId) == null) return
            activeAllocationRecords.remove(allocationId)
            releasedBuffers.incrementAndGet()
            activeBuffers.decrementAndGet()
            activeRequestedBytes.addAndGet(-state.requestedBytes)
            synchronized(state) {
                activeCapacityBytes.addAndGet(-state.trackedCapacityBytes)
                activeDirectCapacityBytes.addAndGet(-state.trackedDirectCapacityBytes)
                state.trackedCapacityBytes = 0
                state.trackedDirectCapacityBytes = 0
            }
            sampleDelegateMetric()
        }

        private fun adjustCapacity(deltaCapacityBytes: Long, deltaDirectCapacityBytes: Long, sampleDelegate: Boolean) {
            if (deltaCapacityBytes == 0L && deltaDirectCapacityBytes == 0L) return
            updateMax(maxActiveCapacityBytes, activeCapacityBytes.addAndGet(deltaCapacityBytes))
            val newActiveDirectCapacityBytes = activeDirectCapacityBytes.addAndGet(deltaDirectCapacityBytes)
            updateMax(maxActiveDirectCapacityBytes, newActiveDirectCapacityBytes)
            maybeCapturePeakActiveDirectReport(newActiveDirectCapacityBytes)
            if (sampleDelegate) {
                sampleDelegateMetric()
            }
        }

        private fun maybeCapturePeakActiveDirectReport(currentActiveDirectBytes: Long) {
            if (!captureAllocationStacks || currentActiveDirectBytes <= 0) return
            while (true) {
                val currentReportedPeak = peakActiveDirectReportBytes.get()
                if (currentActiveDirectBytes <= currentReportedPeak) return
                if (peakActiveDirectReportBytes.compareAndSet(currentReportedPeak, currentActiveDirectBytes)) {
                    val summaryReport = activeAllocationSummaryReport(limit = 20, refreshCapacities = false)
                    val stackReport = unreleasedAllocationReport(limit = 10)
                    if (summaryReport != null || stackReport != null) {
                        val report = buildString {
                            appendLine("activeDirectCapacityBytes=$currentActiveDirectBytes")
                            summaryReport?.let {
                                appendLine("Peak active allocation summary:")
                                append(it)
                            }
                            stackReport?.let {
                                appendLine("Peak active allocation top stacks:")
                                append(it)
                            }
                        }.trimEnd()
                        if (peakActiveDirectReportBytes.get() == currentActiveDirectBytes) {
                            peakActiveDirectReport.set(report)
                        }
                    }
                    return
                }
            }
        }

        private fun refreshActiveCapacities(sampleDelegate: Boolean) {
            activeAllocationStates.values.forEach { refreshActiveCapacity(it, sampleDelegate) }
        }

        private fun refreshActiveCapacity(state: ActiveAllocationState, sampleDelegate: Boolean) {
            var actualCapacityBytes: Long
            var actualDirectCapacityBytes: Long
            try {
                if (state.buffer.refCnt() <= 0) {
                    markReleased(state.id, state)
                    return
                }
                actualCapacityBytes = state.buffer.capacity().toLong()
                actualDirectCapacityBytes = if (state.buffer.isDirect) actualCapacityBytes else 0L
            } catch (e: IllegalReferenceCountException) {
                markReleased(state.id, state)
                return
            }
            synchronized(state) {
                val deltaCapacityBytes = actualCapacityBytes - state.trackedCapacityBytes
                val deltaDirectCapacityBytes = actualDirectCapacityBytes - state.trackedDirectCapacityBytes
                state.trackedCapacityBytes = actualCapacityBytes
                state.trackedDirectCapacityBytes = actualDirectCapacityBytes
                adjustCapacity(deltaCapacityBytes, deltaDirectCapacityBytes, sampleDelegate)
            }
        }

        private fun sampleDelegateMetric() {
            delegateMetric()?.let {
                val usedHeapMemory = it.usedHeapMemory()
                val usedDirectMemory = it.usedDirectMemory()
                val activeDirectMemory = activeDirectCapacityBytes.get()
                val untrackedOrPooledDirectMemory = (usedDirectMemory - activeDirectMemory).coerceAtLeast(0)

                synchronized(delegateMetricSampleLock) {
                    updateMax(maxDelegateUsedHeapMemory, usedHeapMemory)
                    updateMax(maxDelegateUntrackedOrPooledDirectMemory, untrackedOrPooledDirectMemory)
                    val currentMaxDirectMemory = maxDelegateUsedDirectMemory.get()
                    if (usedDirectMemory > currentMaxDirectMemory &&
                        maxDelegateUsedDirectMemory.compareAndSet(currentMaxDirectMemory, usedDirectMemory)
                    ) {
                        activeDirectAtMaxDelegateUsedDirectMemory.set(activeDirectMemory)
                        untrackedOrPooledDirectAtMaxDelegateUsedDirectMemory.set(untrackedOrPooledDirectMemory)
                    }
                }
            }
        }

        private fun delegateMetric(): ByteBufAllocatorMetric? =
            (delegate as? ByteBufAllocatorMetricProvider)?.metric()

        private fun delegateUntrackedOrPooledDirectMemory(): Long =
            delegateMetric()
                ?.let { (it.usedDirectMemory() - activeDirectCapacityBytes.get()).coerceAtLeast(0) }
                ?: -1

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
            private val state: ActiveAllocationState
        ) : WrappedByteBuf(buffer) {
            private val released = AtomicBoolean()

            override fun capacity(newCapacity: Int): ByteBuf {
                val result = super.capacity(newCapacity)
                refreshActiveCapacity(state, sampleDelegate = true)
                return result
            }

            override fun ensureWritable(minWritableBytes: Int): ByteBuf {
                val result = super.ensureWritable(minWritableBytes)
                refreshActiveCapacity(state, sampleDelegate = true)
                return result
            }

            override fun ensureWritable(minWritableBytes: Int, force: Boolean): Int {
                val result = super.ensureWritable(minWritableBytes, force)
                refreshActiveCapacity(state, sampleDelegate = true)
                return result
            }

            override fun release(): Boolean {
                refreshActiveCapacity(state, sampleDelegate = false)
                val deallocated = super.release()
                if (deallocated && released.compareAndSet(false, true)) {
                    release(allocationId, state)
                }
                return deallocated
            }

            override fun release(decrement: Int): Boolean {
                refreshActiveCapacity(state, sampleDelegate = false)
                val deallocated = super.release(decrement)
                if (deallocated && released.compareAndSet(false, true)) {
                    release(allocationId, state)
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
            val maxActiveDirectCapacityBytes: Long,
            val delegateUsedHeapMemory: Long,
            val delegateUsedDirectMemory: Long,
            val delegateUntrackedOrPooledDirectMemory: Long,
            val maxDelegateUsedHeapMemory: Long,
            val maxDelegateUsedDirectMemory: Long,
            val maxDelegateUntrackedOrPooledDirectMemory: Long,
            val activeDirectAtMaxDelegateUsedDirectMemory: Long,
            val untrackedOrPooledDirectAtMaxDelegateUsedDirectMemory: Long
        )
    }

}

private fun TestStarNetworkBuilder2.addIpNodes(nodeCount: Int) {
    (0 until nodeCount).forEach { node(IPManager.Default.getIP(it)) }
}
