package io.libp2p.quicsim.runner

import io.libp2p.core.ConnectionHandler
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.runner.graph.strategy.SimplestAdvanceStrategy
import io.libp2p.quicsim.scenario.QuicNetworkTopology
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.sim.impl.SimNetImpl
import io.libp2p.quicsim.udpnetwork.impl.BasicStarRouteResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

class TimedNetworkControllerTest {

    @Test
    @Timeout(10)
    fun `advances two nodes connecting over quic through one router`() {
        val udpNetwork = QuicNetworkTopology.star(
            hostCount = 2,
            latency = 10.milliseconds,
            bandwidthBytesPerSecond = 1_000_000L,
            hostId = IPManager.Default::getIP
        ).toUdpSimNetwork(BandwidthQueueDiscipline.FIFO)
        val dialerConnected = CompletableFuture<Unit>()
        val listenerConnected = CompletableFuture<Unit>()
        val failure = AtomicReference<Throwable>()
        val runner = SimulatedRunner(
            nodeFactory = object : NodeProgramFactory {
                override fun createNode(id: SimNodeId): NodeProgram =
                    SimpleQuicConnectProgram(
                        simNodeId = id,
                        dialerConnected = dialerConnected,
                        listenerConnected = listenerConnected,
                        failure = failure
                    )
            },
            udpNetwork = udpNetwork
        )
        val nodesStuff = runner.createNodesStuff()

        try {
            val controller = TimedNetworkController(
                simNet = SimNetImpl(nodesStuff.map { it.simNodeImpl }),
                udpNet = udpNetwork,
                routeResolver = BasicStarRouteResolver(udpNetwork),
                timeAdvanceStrategy = SimplestAdvanceStrategy()
            )
            var iterations = 0

            controller.advanceWhile {
                failure.get()?.let { throw AssertionError("Timed QUIC connection failed", it) }
                check(iterations++ < 100_000) {
                    "Timed network did not complete; vertexTimes=${controller.timedGraph.vertices.associate { it.id to it.time }}"
                }
                !(dialerConnected.isDone && listenerConnected.isDone)
            }

            assertTrue(dialerConnected.isDone, "Expected dialer to establish a QUIC connection")
            assertTrue(listenerConnected.isDone, "Expected listener to observe inbound QUIC connection")
        } finally {
            nodesStuff
                .map { it.host.stop() }
                .forEach { it.get(1, TimeUnit.SECONDS) }
        }
    }

    private class SimpleQuicConnectProgram(
        override val simNodeId: SimNodeId,
        private val dialerConnected: CompletableFuture<Unit>,
        private val listenerConnected: CompletableFuture<Unit>,
        private val failure: AtomicReference<Throwable>
    ) : NodeProgram {
        override val completeFuture: CompletableFuture<Unit> = CompletableFuture()

        override fun createProtocols(context: SimContext): List<ProtocolBinding<*>> = emptyList()

        override fun start(simContext: SimContext, networkContext: NetworkContext): CompletableFuture<Unit> =
            if (simNodeId == 0) {
                startDialer(networkContext)
            } else {
                startListener(networkContext)
            }

        private fun startDialer(networkContext: NetworkContext): CompletableFuture<Unit> {
            val targetAddress = networkContext.allNodes.getValue(1)
            val expectedRemotePeerId = targetAddress.getPeerId()

            return networkContext.myHost.network
                .connect(targetAddress)
                .handle { connection, throwable ->
                    if (throwable != null) {
                        fail(throwable)
                    } else {
                        try {
                            assertEquals(expectedRemotePeerId, connection.secureSession().remoteId)
                            dialerConnected.complete(Unit)
                            completeFuture.complete(Unit)
                        } catch (t: Throwable) {
                            fail(t)
                        }
                    }
                    Unit
                }
        }

        private fun startListener(networkContext: NetworkContext): CompletableFuture<Unit> {
            val expectedRemotePeerId = networkContext.allNodes.getValue(0).getPeerId()
            networkContext.myHost.addConnectionHandler(ConnectionHandler.create { connection ->
                try {
                    if (connection.secureSession().remoteId == expectedRemotePeerId) {
                        listenerConnected.complete(Unit)
                        completeFuture.complete(Unit)
                    }
                } catch (t: Throwable) {
                    fail(t)
                }
            })
            return completeFuture
        }

        private fun fail(throwable: Throwable) {
            failure.compareAndSet(null, throwable)
            dialerConnected.completeExceptionally(throwable)
            listenerConnected.completeExceptionally(throwable)
            completeFuture.completeExceptionally(throwable)
        }
    }
}
