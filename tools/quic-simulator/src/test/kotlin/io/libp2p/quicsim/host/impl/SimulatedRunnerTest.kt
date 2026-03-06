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
import io.libp2p.quicsim.network.TestNetworkBuilder
import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network.impl.BasicSimNetwork
import io.libp2p.quicsim.network.impl.BasicSimNetworkEngine
import io.libp2p.quicsim.network.impl.FifoSimQueueDiscipline
import io.libp2p.pubsub.gossip.GossipParams
import io.libp2p.protocol.ProtocolHandler
import io.libp2p.protocol.ProtocolMessageHandler
import io.netty.buffer.ByteBuf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.milliseconds
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
            nodeCount = 2,
            networkEngine = BasicSimNetworkEngine(network)
        )

        try {
            runner.run()
            assertTrue(runner.nodePrograms.all { it.isComplete() }, "Expected all node programs to complete")
        } finally {
            runCatching {
                CompletableFuture.allOf(*runner.hosts.map { it.stop() }.toTypedArray()).get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `simulated runner completes 5-node ring with sample gossip`() {
        val nodeCount = 5
        val publisherCount = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val networkBuilder = TestNetworkBuilder()
        val simNodes = (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = { FifoSimQueueDiscipline(1_000_000L) }
        simNodes.indices.forEach { i ->
            val a = simNodes[i]
            val b = simNodes[(i + 1) % simNodes.size]
            networkBuilder.bidirectional(a, b, Duration.ofMillis(10), qdiscFactory)
        }

        val runner = SimulatedRunner(
            nodeFactory = object : NodeFactory {
                override fun createNode(id: SimNodeId) =
                    SampleGossipNodeProgram(
                        simNodeId = id,
                        connectToNodeIds = listOf((id + 1) % nodeCount),
                        publishersCount = publisherCount,
                        params = GossipParams(),
                        randomSeed = id.toLong(),
                        messageSizeBytes = 1024,
                        initialPublishDelay = 1.seconds
                    ).also { nodePrograms += it }
            },
            nodeCount = nodeCount,
            networkEngine = BasicSimNetworkEngine(networkBuilder.build())
        )

        try {
            runner.run()
            assertTrue(
                nodePrograms.all { it.isComplete() },
                "Expected all sample gossip node programs to complete"
            )
        } finally {
            runCatching {
                CompletableFuture.allOf(*runner.hosts.map { it.stop() }.toTypedArray()).get(10, TimeUnit.SECONDS)
            }
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

        val builder = TestNetworkBuilder()
        val node0 = builder.node("node-0")
        val node1 = builder.node("node-1")
        builder.bidirectional(
            node0,
            node1,
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
            nodeCount = 2,
            networkEngine = BasicSimNetworkEngine(builder.build())
        )

        try {
            runner.run()
        } finally {
            runCatching {
                CompletableFuture.allOf(*runner.hosts.map { it.stop() }.toTypedArray()).get(10, TimeUnit.SECONDS)
            }
        }

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
            val addr = networkContext.allNodes[targetNodeId] ?: throw IllegalStateException("Node $targetNodeId not found")
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
