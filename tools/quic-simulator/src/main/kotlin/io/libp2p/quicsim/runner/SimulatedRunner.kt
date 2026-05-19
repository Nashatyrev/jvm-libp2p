package io.libp2p.quicsim.runner

import io.libp2p.core.Host
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.dsl.host
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.transport.Transport
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.etc.types.forward
import io.libp2p.quicsim.SimLogger
import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.sim.impl.SimNetImpl
import io.libp2p.quicsim.sim.impl.SimNodeImpl
import io.libp2p.quicsim.sim.impl.netty.SimNodeDatagramChannelFactory
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.transport.quic.QuicTransport
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SimulatedRunner(
    val nodeFactory: NodeProgramFactory,
    val networkEngine: UdpSimNetworkEngine,
    val ipManager: IPManager = IPManager.Default,
    val listenPortStartRange: Int = 17000,
    val maxSimulatedRunDuration: Duration = 1.minutes,
    val random: SecureRandom = SecureRandom(byteArrayOf(100)),
    val latencyWindowParallelism: Int = 0,
) {
    val nodeCount: Int = networkEngine.network.nodes.size

    lateinit var simTimer: MonotonicTimer

    class NodeStuff(
        val id: SimNodeId,
    ) {
        lateinit var nodeProgram: NodeProgram
        lateinit var nodeScheduler: DeterministicScheduler
        lateinit var simNodeImpl: SimNodeImpl
        lateinit var simContext: SimContext
        lateinit var networkContext: NetworkContext
        lateinit var host: Host
        val startFuture: CompletableFuture<Unit> = CompletableFuture()

        fun startProgram() {
            val startFut = nodeProgram.start(simContext, networkContext)
            startFut.forward(startFuture)
        }
    }

    fun createNodesStuff(): List<NodeStuff> {
        val nodeStuffs = List(nodeCount) { simNodeId ->
            val stuff = NodeStuff(simNodeId)
            stuff.nodeProgram = nodeFactory.createNode(simNodeId)
            val scheduler = DeterministicScheduler()
            scheduler.executeAfterDelay(Duration.ZERO, stuff::startProgram)
            stuff.nodeScheduler = scheduler
            stuff.simNodeImpl = SimNodeImpl(
                nodeId = simNodeId,
                scheduler = scheduler,
                ip = ipManager.getIP(simNodeId)
            )
            stuff.simContext = SimContext(scheduler, scheduler)
            stuff
        }

        // Creating hosts in parallel for perf
        nodeStuffs
            .parallelStream()
            .forEach { stuff ->
                stuff.host = createHost(stuff.nodeProgram, stuff.simContext, stuff.simNodeImpl)
            }

        startHosts(nodeStuffs.map { it.host })

        val allListenAddresses = nodeStuffs
            .associate { it.id to it.host.listenAddresses().first() }

        nodeStuffs.forEach { nodeStuff ->
            nodeStuff.networkContext = NetworkContext(nodeStuff.host, allListenAddresses)
        }

        return nodeStuffs
    }

    private fun createHost(nodeProgram: NodeProgram, simContext: SimContext, node: SimNodeImpl): Host {
        val protocols = nodeProgram.createProtocols(simContext)
        val port = listenPortStartRange + nodeProgram.simNodeId
        val listenIP = node.ip

        val transportFactory = BiFunction<PrivKey, List<ProtocolBinding<*>>, Transport> { key, selectedProtocols ->
            QuicTransport(
                key,
                "ECDSA",
                selectedProtocols,
                datagramChannelFactory = SimNodeDatagramChannelFactory(node)
            )
        }

        return HostBuilder(HostBuilder.DefaultMode.None)
            .secureTransport(transportFactory)
            .protocol(*(protocols.toTypedArray()))
            .listen("/ip4/$listenIP/udp/$port/quic-v1")
            .builderModifier {
                nodeProgram.modifyBuilder(simContext, it)
                it.identity.factory = { generateEd25519KeyPair(random).first }
            }
            .build()
    }

    fun startHosts(hosts: List<Host>) {
        val startFutures = hosts
            .parallelStream()
            .map { it.start() }
        startFutures.forEach { it.get(1, TimeUnit.SECONDS) }
    }

    fun startPrograms(nodesStuff: List<NodeStuff>): CompletableFuture<Void> {
        val futures = nodesStuff.map { it.startFuture }
        return CompletableFuture.allOf(*futures.toTypedArray())
    }

    fun run() {
        println("Creating hosts...")
        val nodesStuff = createNodesStuff()
        val nodePrograms = nodesStuff.map { it.nodeProgram }
        val embeddedNodes = nodesStuff.map { it.simNodeImpl }
        println("Creating sim network...")
        val simCoreNet = SimNetImpl(embeddedNodes)
        val idAndIp =
            embeddedNodes.map { node ->
                SimPacketPump.IdMapEntry(networkEngine.network.nodes[node.nodeId].id, node.ip)
            }
        val latencyWindowPump =
            if (latencyWindowParallelism > 0) {
                LatencyWindowSimPacketPump(embeddedNodes, networkEngine.network, idAndIp, latencyWindowParallelism)
            } else {
                null
            }
        val sequentialPacketPump = if (latencyWindowPump == null) {
            SimPacketPump(simCoreNet, networkEngine, idAndIp)
        } else {
            null
        }
        val simPacketPump = latencyWindowPump ?: sequentialPacketPump!!
        simTimer = latencyWindowPump?.monotonicTimer ?: sequentialPacketPump!!.monotonicTimer
        val logger = SimLogger(simTimer)
        logger.log("Starting hosts...")
//        startHosts(nodesStuff.map { it.host })
        logger.log("Starting programs...")
        val startFuture = startPrograms(nodesStuff)
        startFuture.handle { _, throwable ->
            if (throwable != null) {
                logger.log("Error on starting programs...")
                throwable.printStackTrace()
                throw throwable
            } else {
                logger.log("All connected and all programs are started.")
            }
        }
        logger.log("Running simulated event loop...")

        val startSimT = simTimer.time()
        var lastLogSimT = startSimT
        var ticksCount = 0L
        var nextAdvance: Duration = Duration.Companion.ZERO
        try {
            while (true) {
                simPacketPump.advanceAndExecuteAll(nextAdvance)

                val maybeNextAdvance = simPacketPump.nextTaskDuration()

                val completeCount = nodePrograms.count { it.isComplete() }
                if (completeCount == nodePrograms.size) {
                    break
                }

                nextAdvance = maybeNextAdvance
                    ?: throw IllegalStateException("Simulation stalled: no pending tasks but only $completeCount/$nodeCount programs completed")

                val simTime = simTimer.time()
                ticksCount++
                if (simTime - lastLogSimT >= 10.seconds) {
                    lastLogSimT = simTime
                    logger.log("Nodes complete $completeCount of $nodeCount in $ticksCount ticks")
                }

                if (simTime - startSimT > maxSimulatedRunDuration) {
                    throw IllegalStateException(
                        "Simulation exceeded limit: simulated=${(simTime - startSimT).inWholeMilliseconds}ms " +
                                "limit=${maxSimulatedRunDuration.inWholeMilliseconds}ms " +
                                "completed=$completeCount/${nodePrograms.size}"
                    )
                }
            }

            logger.log("All complete in $ticksCount ticks")
        } catch (e: Exception) {
            logger.log("Exception: $e")
            throw e
        } finally {
            latencyWindowPump?.close()
        }
    }
}
