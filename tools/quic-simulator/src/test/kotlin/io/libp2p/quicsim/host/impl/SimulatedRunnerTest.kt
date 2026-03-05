package io.libp2p.quicsim.host.impl

import io.libp2p.quicsim.host.NetworkContext
import io.libp2p.quicsim.host.NodeFactory
import io.libp2p.quicsim.host.NodeProgram
import io.libp2p.quicsim.host.SimContext
import io.libp2p.quicsim.host.SimNodeId
import io.libp2p.quicsim.network.TestNetworkBuilder
import io.libp2p.quicsim.network.SimNode
import io.libp2p.quicsim.network.impl.BasicSimNetwork
import io.libp2p.quicsim.network.impl.BasicSimNetworkEngine
import io.libp2p.quicsim.network.impl.FifoSimQueueDiscipline
import io.libp2p.pubsub.gossip.GossipParams
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimulatedRunnerTest {

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
    @Timeout(30)
    fun `simulated runner completes 5-node ring with sample gossip`() {
        val nodeCount = 5
        val nodePrograms = mutableListOf<SampleGossipNodeProgram>()
        val networkBuilder = TestNetworkBuilder()
        val simNodes = (0 until nodeCount).map { networkBuilder.node("node-$it") }
        val qdiscFactory = { FifoSimQueueDiscipline(1_000_000L) }
        simNodes.indices.forEach { i ->
            val a = simNodes[i]
            val b = simNodes[(i + 1) % simNodes.size]
            networkBuilder.bidirectional(a, b, Duration.ofMillis(1), qdiscFactory)
        }

        val runner = SimulatedRunner(
            nodeFactory = object : NodeFactory {
                override fun createNode(id: SimNodeId) =
                    SampleGossipNodeProgram(
                        simNodeId = id,
                        connectToNodeIds = listOf((id + 1) % nodeCount),
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
}
