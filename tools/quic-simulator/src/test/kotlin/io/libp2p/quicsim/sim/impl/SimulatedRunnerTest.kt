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
import io.libp2p.quicsim.udpnetwork.impl.FifoUdpSimQueueDiscipline
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl2
import io.netty.buffer.ByteBuf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

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
        val qdiscFactory = fifoQDiscFactory(1_000_000L)
        networkBuilder.linkAllToRouter(Duration.ofMillis(50), qdiscFactory)

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
            networkEngine = UdpSimNetworkEngineImpl(networkBuilder.build())
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.isComplete() },
            "Expected all sample gossip node programs to complete"
        )
    }

    @Test
    fun sendMessageFromNPublishers() {
        val nodeCount = 100
        val publishersCount = 100
        val neighboursToConnect = 20
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val randomConnectionsByNode: Map<SimNodeId, List<SimNodeId>> =
            QuicScenarios.createBidirectionalRandomTopology(nodeCount, neighboursToConnect, seed = 1234)

        val networkBuilder = TestStarNetworkBuilder2()
        (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = fifoQDiscFactory(5_000_000L)
        networkBuilder.linkAllToRouter(Duration.ofMillis(10), qdiscFactory).build()

        class LoggingUdpNetworkEngineUdp(val delegate: UdpSimNetworkEngine) : UdpSimNetworkEngine by delegate {
            private var simTime: kotlin.time.Duration = kotlin.time.Duration.ZERO
            var packetsCount = 0L
            var throughputBytes = 0L
            override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
                val outbound = delegate.deliver(inboundData)
                packetsCount += outbound.size
                throughputBytes += outbound.sumOf { it.bytes }
                return outbound
            }

            override fun advanceAndExecuteAll(advanceDuration: kotlin.time.Duration) {
                delegate.advanceAndExecuteAll(advanceDuration)
                simTime += advanceDuration
            }

            override fun nextTaskDuration(): kotlin.time.Duration? {
                val nextTaskDuration = delegate.nextTaskDuration()
                return nextTaskDuration
            }
        }

        val udpNetwork = UdpSimNetworkEngineImpl2(networkBuilder.build())
        val udpNetworkLogging = LoggingUdpNetworkEngineUdp(udpNetwork)


        val runner = SimulatedRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: SimNodeId): NodeProgram =
                    SampleGossipNodeProgram(
                        simNodeId = id,
                        connectToNodeIds = randomConnectionsByNode.getValue(id),
                        publishersCount = publishersCount,
                        params = GossipParams(),
                        randomSeed = id.toLong(),
                        messageSizeBytes = 1024,
                        initialPublishDelay = 30.seconds,
                    ).also { nodePrograms += it }
            },
            networkEngine = udpNetworkLogging,
            maxSimulatedRunDuration = 10.minutes,
            latencyWindowParallelism = 0
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.isComplete() },
            "Expected all sample gossip node programs to complete in 1000-node scenario"
        )

        println("Total packet count: " + udpNetworkLogging.packetsCount + ", bytes: " + udpNetworkLogging.throughputBytes)
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
            Duration.ofMillis(10),
            qdiscFactory = fifoQDiscFactory(1_000_000L)
        )

        val runner = SimulatedRunner(
            nodeFactory = factory,
            networkEngine = UdpSimNetworkEngineImpl(builder.build()),
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
            latency = 100.milliseconds.toJavaDuration(),
            qdiscFactory = fifoQDiscFactory(1_000_000L)
        )

        val runner = SimulatedRunner(
            nodeFactory = factory,
            networkEngine = UdpSimNetworkEngineImpl(builder.build()),
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
            Duration.ofMillis(100),
            qdiscFactory = fifoQDiscFactory(10_000L)
        )

        class LoggingUdpNetworkEngineUdp(val delegate: UdpSimNetworkEngine) : UdpSimNetworkEngine by delegate {
            private var simTime: kotlin.time.Duration = kotlin.time.Duration.ZERO
            override fun deliver(inboundData: List<UdpSimPacket>): List<UdpSimPacket> {
                fun simPacketStr(packet: UdpSimPacket) =
                    "[$simTime] ${packet.srcNodeId} ==> ${packet.dstNodeId} size: ${packet.bytes}, hash: ${packet.payloadRef.hashCode()}"

                for (packet in inboundData) {
                    println("  ... " + simPacketStr(packet))
                }
                val outbound = delegate.deliver(inboundData)
                for (packet in outbound) {
                    println(simPacketStr(packet))
                }
                return outbound
            }

            override fun advanceAndExecuteAll(advanceDuration: kotlin.time.Duration) {
                println(" Advance $advanceDuration")
                delegate.advanceAndExecuteAll(advanceDuration)
                simTime += advanceDuration
            }

            override fun nextTaskDuration(): kotlin.time.Duration? {
                val nextTaskDuration = delegate.nextTaskDuration()
                println(" Next task duration: $nextTaskDuration")
                return nextTaskDuration
            }
        }

        val udpNetwork = UdpSimNetworkEngineImpl(builder.build())
        val udpNetworkLogging = LoggingUdpNetworkEngineUdp(udpNetwork)

        val runner = SimulatedRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: SimNodeId): NodeProgram =
                    object : NodeProgram {
                        override val simNodeId: SimNodeId = id
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
                                    .thenApply { Unit }
                            } else {
                                CompletableFuture.completedFuture(Unit)
                            }
                        }

                        override fun isComplete(): Boolean {
                            return myHost?.network?.connections?.isNotEmpty() ?: false
                        }

                    }
            },
            networkEngine = udpNetworkLogging
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
            Duration.ofMillis(linkLatencyMs),
            qdiscFactory = fifoQDiscFactory(bandwidthBytesPerSec)
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
            networkEngine = UdpSimNetworkEngineImpl(builder.build()),
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
        @Volatile
        private var complete = false

        override fun createProtocols(context: SimContext) = emptyList<io.libp2p.core.multistream.ProtocolBinding<*>>()

        override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
            return simContext.scheduler.submitAfterDelay(100.milliseconds) {
                complete = true
            }
        }

        override fun isComplete(): Boolean = complete
    }

    private companion object {
        fun fifoQDiscFactory(bandwidthBytesPerSec: Long): (Duration) -> FifoUdpSimQueueDiscipline = { latency ->
            FifoUdpSimQueueDiscipline(
                bandwidth = Bandwidth(bandwidthBytesPerSec),
                latency = latency.toKotlinDuration()
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
        private lateinit var binding: SizeEchoBinding

        @Volatile
        private var complete = false

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
                        complete = true
                    } else {
                        complete = false
                    }
                }
        }

        override fun isComplete(): Boolean = complete
    }

    private class PassiveEchoNodeProgram(
        override val simNodeId: SimNodeId
    ) : NodeProgram {
        @Volatile
        private var started = false

        override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> =
            listOf(SizeEchoBinding(SizeEchoProtocol()))

        override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> {
            started = true
            return CompletableFuture.completedFuture(Unit)
        }

        override fun isComplete(): Boolean = started
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
}
