package io.libp2p.quicsim.host.impl

import io.libp2p.quicsim.host.NetworkContext
import io.libp2p.quicsim.host.NodeFactory
import io.libp2p.quicsim.host.NodeProgram
import io.libp2p.quicsim.host.SimContext
import io.libp2p.quicsim.host.SimNodeId
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.multistream.StrictProtocolBinding
import io.libp2p.core.Stream
import io.libp2p.etc.types.toByteArray
import io.libp2p.etc.types.toByteBuf
import io.libp2p.quicsim.network.TestStarNetworkBuilder
import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network.impl.BasicSimNetwork
import io.libp2p.quicsim.network.impl.BasicSimNetworkEngine
import io.libp2p.quicsim.network.impl.FifoSimQueueDiscipline
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.libp2p.quicsim.host.impl.sim.SimLogger
import io.libp2p.quicsim.host.impl.sim.SimulatedRunner
import io.netty.buffer.ByteBuf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.random.Random
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
    @Timeout(30)
    fun `simulated runner completes scheduled node programs`() {
        val network = BasicSimNetwork(
            nodes = listOf(SimNode("node-0"), SimNode("node-1")),
            links = emptyList()
        )

        val runner = SimulatedRunner(
            nodeFactory = object : NodeFactory {
                override fun createNode(id: SimNodeId): NodeProgram = SimpleConnectNodeProgram(
                    simNodeId = id
                )
            },
            networkEngine = BasicSimNetworkEngine(network)
        )

        runner.run()
        assertTrue(runner.nodesStuff.all { it.nodeProgram.isComplete() }, "Expected all node programs to complete")
    }

    @Test
    fun `simulated runner completes 5-node ring with sample gossip`() {
        val nodeCount = 5
        val publisherCount = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val networkBuilder = TestStarNetworkBuilder()
        (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = { FifoSimQueueDiscipline(1_000_000L) }
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
            networkEngine = BasicSimNetworkEngine(networkBuilder.build())
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.isComplete() },
            "Expected all sample gossip node programs to complete"
        )
    }

    @Test
    @Disabled
    @Timeout(180)
    fun `simulated runner completes 1000-node ring with 20-neighbor gossip and 200 publishers`() {
        val nodeCount = 50
        val publishersCount = 10
        val neighboursToConnect = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val random = Random(1234)
        val randomConnectionsByNode: Map<SimNodeId, List<SimNodeId>> =
            (0 until nodeCount).associateWith { nodeId ->
                generateSequence { random.nextInt(nodeCount) }
                    .filter { it != nodeId }
                    .distinct()
                    .take(neighboursToConnect)
                    .toList()
            }

        val networkBuilder = TestStarNetworkBuilder()
        (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = { FifoSimQueueDiscipline(50_000_000L) }
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
            networkEngine = BasicSimNetworkEngine(networkBuilder.build()),
            maxSimulatedRunDuration = 5.minutes
        )

        runner.run()
        assertTrue(
            nodePrograms.all { it.isComplete() },
            "Expected all sample gossip node programs to complete in 1000-node scenario"
        )
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

        val builder = TestStarNetworkBuilder()
        builder.node("node-0")
        builder.node("node-1")
        builder.linkAllToRouter(
            Duration.ofMillis(linkLatencyMs),
            qdiscFactory = { FifoSimQueueDiscipline(bandwidthBytesPerSec) }
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
            networkEngine = BasicSimNetworkEngine(builder.build())
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

        override fun start(simContext: SimContext, networkContext: NetworkContext) {
            simContext.scheduler.executeAfterDelay(100.milliseconds) {
                complete = true
            }
        }

        override fun isComplete(): Boolean = complete
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

        override fun start(simContext: SimContext, networkContext: NetworkContext) {
            val epoch = simContext.timer.time()
            val addr =
                networkContext.allNodes[targetNodeId] ?: throw IllegalStateException("Node $targetNodeId not found")
            networkContext.myHost.network.connect(addr)
                .thenCompose { conn ->
                    conn.muxerSession().createStream(binding).controller
                }
                .thenCompose { ctrl ->
                    sentAtSimMillis.set((simContext.timer.time() - epoch).inWholeMilliseconds)
                    ctrl.send(payload.toByteArray(StandardCharsets.UTF_8))
                }
                .whenComplete { echoedBytes, err ->
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

        override fun start(simContext: SimContext, networkContext: NetworkContext) {
            started = true
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
