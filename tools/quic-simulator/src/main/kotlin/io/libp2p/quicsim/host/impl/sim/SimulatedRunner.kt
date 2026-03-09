package io.libp2p.quicsim.host.impl.sim

import io.libp2p.core.Host
import io.libp2p.core.crypto.KeyType
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.transport.Transport
import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.host.NetworkContext
import io.libp2p.quicsim.host.NodeFactory
import io.libp2p.quicsim.host.NodeProgram
import io.libp2p.quicsim.host.SimContext
import io.libp2p.quicsim.host.SimNodeId
import io.libp2p.quicsim.network.SimNetworkEngine
import io.libp2p.transport.quic.QuicTransport
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SimulatedRunner(
    val nodeFactory: NodeFactory,
    val networkEngine: SimNetworkEngine,
    val ipManager: IPManager = IPManager.Default,
    val listenPortStartRange: Int = 17000,
    val maxSimulatedRunDuration: Duration = 1.minutes,
) {
    val nodeCount: Int = networkEngine.network.nodes.size

    lateinit var nodesStuff: List<NodeStuff>
    lateinit var simTimer: MonotonicTimer

    data class NodeStuff(
        val nodeProgram: NodeProgram,
        val nodeScheduler: DeterministicScheduler,
        val embeddedNode: EmbeddedNode,
        val simContext: SimContext,
        val networkContext: NetworkContext,
        val host: Host
    )

    fun createNodesStuff(): List<NodeStuff> {
        val nodePrograms = List(nodeCount, nodeFactory::createNode)

        val nodeSchedulers = List(nodeCount) { DeterministicScheduler() }

        val embeddedNodes = List(nodeCount) { i ->
            val program = nodePrograms[i]
            EmbeddedNode(
                nodeId = program.simNodeId,
                scheduler = nodeSchedulers[i],
                ip = ipManager.getIP(program.simNodeId)
            )
        }

        val simContexts = List(nodeCount) {
            SimContext(nodeSchedulers[it], nodeSchedulers[it])
        }

        val hosts = List(nodeCount) { idx ->
            createHost(nodePrograms[idx], simContexts[idx], embeddedNodes[idx])
        }

        startHosts(hosts)

        val allListenAddresses = hosts.indices.associateWith { idx ->
            hosts[idx].listenAddresses().first()
        }

        val networkContexts = hosts.map { NetworkContext(it, allListenAddresses) }

        return List(nodeCount) { i->
            NodeStuff(nodePrograms[i], nodeSchedulers[i], embeddedNodes[i], simContexts[i], networkContexts[i], hosts[i])
        }
    }

    private fun createHost(nodeProgram: NodeProgram, simContext: SimContext, node: EmbeddedNode): Host {
        val protocols = nodeProgram.createProtocols(simContext)
        val port = listenPortStartRange + nodeProgram.simNodeId
        val listenIP = node.ip

        val transportFactory = BiFunction<PrivKey, List<ProtocolBinding<*>>, Transport> { key, selectedProtocols ->
            QuicTransport(
                key,
                "ECDSA",
                selectedProtocols,
                datagramChannelFactory = EmbeddedNodeDatagramChannelFactory(node)
            )
        }

        return HostBuilder(HostBuilder.DefaultMode.None)
            .keyType(KeyType.ED25519)
            .secureTransport(transportFactory)
            .protocol(*(protocols.toTypedArray()))
            .listen("/ip4/$listenIP/udp/$port/quic-v1")
            .builderModifier { nodeProgram.modifyBuilder(simContext, it) }
            .build()
    }

    fun startHosts(hosts: List<Host>) {
        val startFutures = hosts.map { it.start() }
        startFutures.forEach { it.get(1, TimeUnit.SECONDS) }
    }

    fun startPrograms(nodesStuff: List<NodeStuff>) {
        nodesStuff.forEach { nodeStuff ->
            nodeStuff.nodeProgram.start(nodeStuff.simContext, nodeStuff.networkContext)
        }
    }

    fun run() {
        println("Creating hosts...")
        nodesStuff = createNodesStuff()
        val nodePrograms = nodesStuff.map { it.nodeProgram }
        val embeddedNodes = nodesStuff.map { it.embeddedNode }
        println("Creating sim network...")
        val simCoreNet = SimCoreNetImpl(embeddedNodes)
        val idAndIp =
            embeddedNodes.map { node ->
                SimPacketPump.IdMapEntry(networkEngine.network.nodes[node.nodeId].id, node.ip)
            }
        val simPacketPump = SimPacketPump(simCoreNet, networkEngine, idAndIp)
        simTimer = simPacketPump.monotonicTimer
        val logger = SimLogger(simTimer)
        logger.log("Starting hosts...")
//        startHosts(nodesStuff.map { it.host })
        logger.log("Starting programs...")
        startPrograms(nodesStuff)
        logger.log("Running simulated event loop...")

        val startSimT = simTimer.time()
        var lastLogSimT = startSimT
        var ticksCount = 0L
        var nextAdvance: Duration = Duration.ZERO
        try {
            while (true) {
                simPacketPump.advanceAndExecuteAll(nextAdvance)
                val simTime = simTimer.time()
                ticksCount++

                val completeCount = nodePrograms.count { it.isComplete() }
                if (completeCount == nodePrograms.size) {
                    break
                }

                if (simTime - lastLogSimT >= 10.seconds) {
                    lastLogSimT = simTime
                    logger.log("Nodes complete $completeCount of $nodeCount in $ticksCount ticks")
                }

                nextAdvance = simPacketPump.nextTaskDuration()
                    ?: throw IllegalStateException("Simulation stalled: no pending tasks but only $completeCount/$nodeCount programs completed")

                if (simTime - startSimT > maxSimulatedRunDuration) {
                    throw IllegalStateException(
                        "Simulation exceeded limit: simulated=${(simTime - startSimT).inWholeMilliseconds}ms " +
                                "limit=${maxSimulatedRunDuration.inWholeMilliseconds}ms " +
                                "completed=$completeCount/${nodePrograms.size}")
                }
            }

            logger.log("All complete...")
        } catch (e: Exception) {
            logger.log("Exception: $e")
            throw e
        }
    }
}

