package io.libp2p.quicsim.host.impl

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
import io.libp2p.quicsim.host.NetworkContext
import io.libp2p.quicsim.host.NodeFactory
import io.libp2p.quicsim.host.NodeProgram
import io.libp2p.quicsim.host.SimContext
import io.libp2p.quicsim.host.SimNodeId
import io.libp2p.quicsim.host.impl.sim.SimulatedRunner
import io.libp2p.quicsim.network2.Bandwidth
import io.libp2p.quicsim.network2.SimNetworkEngine2
import io.libp2p.quicsim.network2.SimNode
import io.libp2p.quicsim.network2.SimPacket
import io.libp2p.quicsim.network2.TestStarNetworkBuilder2
import io.libp2p.quicsim.network2.impl.BasicSimNetwork2
import io.libp2p.quicsim.network2.impl.FifoSimQueueDiscipline2
import io.libp2p.quicsim.network2.impl.SimNetworkEngine2Impl
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
    @Timeout(30)
    fun `simulated runner completes scheduled node programs`() {
        val network = BasicSimNetwork2(
            nodes = listOf(SimNode("node-0"), SimNode("node-1")),
            links = emptyList()
        )

        val runner = SimulatedRunner(
            nodeFactory = object : NodeFactory {
                override fun createNode(id: SimNodeId): NodeProgram = SimpleConnectNodeProgram(
                    simNodeId = id
                )
            },
            networkEngine = SimNetworkEngine2Impl(network)
        )

        runner.run()
        assertTrue(runner.nodesStuff.all { it.nodeProgram.isComplete() }, "Expected all node programs to complete")
    }

    @Test
    fun `simulated runner completes 5-node ring with sample gossip`() {
        val nodeCount = 5
        val publisherCount = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val networkBuilder = TestStarNetworkBuilder2()
        (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = fifoQDiscFactory(1_000_000L)
        networkBuilder.linkAllToRouter( Duration.ofMillis(50), qdiscFactory)

        val runner = SimulatedRunner(
            nodeFactory = object : NodeFactory {
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
            networkEngine = SimNetworkEngine2Impl(networkBuilder.build())
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.isComplete() },
            "Expected all sample gossip node programs to complete"
        )
    }

    @Test
    @Timeout(180)
    fun `simulated runner completes`() {
        val nodeCount = 10
        val publishersCount = 10
        val neighboursToConnect = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val randomConnectionsByNode: Map<SimNodeId, List<SimNodeId>> =
            createBidirectionalRandomTopology(nodeCount, neighboursToConnect, seed = 1234)

        val networkBuilder = TestStarNetworkBuilder2()
        (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = fifoQDiscFactory(50_000L)
        networkBuilder.linkAllToRouter(Duration.ofMillis(50), qdiscFactory).build()

        val runner = SimulatedRunner(
            nodeFactory = object : NodeFactory {
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
            networkEngine = SimNetworkEngine2Impl(networkBuilder.build()),
            maxSimulatedRunDuration = 10.minutes
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.isComplete() },
            "Expected all sample gossip node programs to complete in 1000-node scenario"
        )
    }

    private fun createBidirectionalRandomTopology(
        nodeCount: Int,
        neighboursToConnect: Int,
        seed: Int
    ): Map<SimNodeId, List<SimNodeId>> {
        require(neighboursToConnect in 0 until nodeCount) {
            "neighboursToConnect must be in [0, $nodeCount), got $neighboursToConnect"
        }
        require((nodeCount * neighboursToConnect) % 2 == 0) {
            "nodeCount * neighboursToConnect must be even for bidirectional topology"
        }

        val random = Random(seed)
        val permutation = (0 until nodeCount).shuffled(random)
        val adjacency = MutableList(nodeCount) { mutableSetOf<Int>() }

        val evenDegree = neighboursToConnect and 1.inv()
        val half = evenDegree / 2
        for (i in permutation.indices) {
            val a = permutation[i]
            for (step in 1..half) {
                val b = permutation[(i + step) % nodeCount]
                adjacency[a] += b
                adjacency[b] += a
            }
        }

        if ((neighboursToConnect and 1) == 1) {
            require(nodeCount % 2 == 0) { "Odd degree requires even nodeCount" }
            val halfNodes = nodeCount / 2
            for (i in 0 until halfNodes) {
                val a = permutation[i]
                val b = permutation[(i + halfNodes) % nodeCount]
                adjacency[a] += b
                adjacency[b] += a
            }
        }

        check(adjacency.all { it.size == neighboursToConnect }) {
            "Failed to generate bidirectional topology: nodeCount=$nodeCount degree=$neighboursToConnect"
        }

        return adjacency
            .mapIndexed { nodeId, peers -> nodeId to peers.toList().sorted() }
            .toMap()
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
    fun `2 nodes connect to each other`() {
        val builder = TestStarNetworkBuilder2()
        builder.node("node-0")
        builder.node("node-1")
        builder.linkAllToRouter(
            Duration.ofMillis(100),
            qdiscFactory = fifoQDiscFactory(10_000L)
        )

        class LoggingUdpNetworkEngine(val delegate: SimNetworkEngine2) : SimNetworkEngine2 by delegate {
            private var simTime: kotlin.time.Duration = kotlin.time.Duration.ZERO
            override fun deliver(inboundData: List<SimPacket>): List<SimPacket> {
                fun simPacketStr(packet: SimPacket) =
                    "[$simTime] ${packet.srcNodeId} ==> ${packet.dstNodeId} size: ${packet.bytes}"

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
        val udpNetwork = SimNetworkEngine2Impl(builder.build())
        val udpNetworkLogging = LoggingUdpNetworkEngine(udpNetwork)

        val runner = SimulatedRunner(
            nodeFactory = object : NodeFactory {
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
        bandwidthBytesPerSec: Long
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
            nodeFactory = object : NodeFactory {
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
            networkEngine = SimNetworkEngine2Impl(builder.build())
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
        fun fifoQDiscFactory(bandwidthBytesPerSec: Long): (Duration) -> FifoSimQueueDiscipline2 = { latency ->
            FifoSimQueueDiscipline2(
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
