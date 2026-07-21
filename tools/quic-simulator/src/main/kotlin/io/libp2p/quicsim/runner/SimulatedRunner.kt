package io.libp2p.quicsim.runner

import io.libp2p.core.Host
import io.libp2p.core.crypto.PrivKey
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multistream.ProtocolBinding
import io.libp2p.core.transport.Transport
import io.libp2p.crypto.keys.generateEd25519KeyPair
import io.libp2p.etc.types.forward
import io.libp2p.quicsim.SimLogger
import io.libp2p.quicsim.core.PacketProcessorVisitor
import io.libp2p.quicsim.core.schedule.DeterministicScheduler
import io.libp2p.quicsim.core.schedule.MonotonicTimer
import io.libp2p.quicsim.program.NodeProgram
import io.libp2p.quicsim.program.NodeProgramFactory
import io.libp2p.quicsim.sim.NetworkContext
import io.libp2p.quicsim.sim.SimContext
import io.libp2p.quicsim.sim.SimNodeId
import io.libp2p.quicsim.sim.SimNodeVisitorFactory
import io.libp2p.quicsim.sim.impl.SimNetImpl
import io.libp2p.quicsim.sim.impl.SimNodeImpl
import io.libp2p.quicsim.sim.impl.netty.SimNodeDatagramChannelFactory
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNetworkEngine
import io.libp2p.transport.quic.QuicTransport
import io.netty.buffer.AdaptiveByteBufAllocator
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.socket.DatagramPacket
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BiFunction
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SimulatedRunner(
    val nodeFactory: NodeProgramFactory,
    val udpNetwork: UdpSimNetwork,
    val ipManager: IPManager = IPManager.Default,
    val listenPortStartRange: Int = 17000,
    val maxSimulatedRunDuration: Duration = 1.minutes,
    val random: SecureRandom = SecureRandom(byteArrayOf(100)),
    val latencyWindowParallelism: Int = 0,
    val nodeVisitorFactory: SimNodeVisitorFactory<DatagramPacket> =
        SimNodeVisitorFactory { PacketProcessorVisitor.none() },
    val datagramPacketTraceRecorder: DatagramPacketTraceRecorder = DatagramPacketTraceRecorder.Noop,
    val quicAllocatorFactory: (SimNodeId) -> ByteBufAllocator = defaultQuicAllocatorFactoryFromSystemProperties(),
    val nodeHeapProfiler: SimulatedNodeHeapProfiler = SimulatedNodeHeapProfiler.fromSystemProperties()
) {
    val nodeCount: Int = udpNetwork.nodes.size

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
        val completeTimeFuture: CompletableFuture<Duration> = CompletableFuture()

        fun startProgram() {
            nodeProgram.completeFuture
                .thenApply {
                    simNodeImpl.nodeTime.elapsedTime()
                }
                .forward(completeTimeFuture)
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
            val ip = ipManager.getIP(simNodeId)
            stuff.simNodeImpl = SimNodeImpl(
                nodeId = simNodeId,
                scheduler = scheduler,
                ip = ip,
                nodeVisitor = nodeVisitorFactory.create(ip)
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
        val allocator = quicAllocatorFactory(nodeProgram.simNodeId)

        val transportFactory = BiFunction<PrivKey, List<ProtocolBinding<*>>, Transport> { key, selectedProtocols ->
            QuicTransport(
                key,
                "ECDSA",
                selectedProtocols,
                datagramChannelFactory = TracingDatagramChannelFactory(
                    delegate = SimNodeDatagramChannelFactory(node, allocator),
                    nodeId = nodeProgram.simNodeId,
                    timeSupplier = { simContext.timer.elapsedTime() },
                    traceRecorder = datagramPacketTraceRecorder
                ),
                allocator = allocator
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
        nodeHeapProfiler.sample("after_create_nodes", Duration.ZERO, nodesStuff)
        val nodePrograms = nodesStuff.map { it.nodeProgram }
        val embeddedNodes = nodesStuff.map { it.simNodeImpl }
        println("Creating sim network...")
        val simCoreNet = SimNetImpl(embeddedNodes)
        val nodeIps = embeddedNodes.map { it.ip }.toSet()
        require(udpNetwork.nodes.map { it.id }.toSet() == nodeIps) {
            "UdpSimNetwork endpoint ids must match simulated node IPs"
        }
        val simPacketPump =
            if (latencyWindowParallelism > 0) {
                ParallelSimPacketBridge(simCoreNet, udpNetwork, latencyWindowParallelism)
            } else {
                SimpleSimPacketBridge(simCoreNet, udpNetwork)
            }
        simTimer = simPacketPump.monotonicTimer
        nodeHeapProfiler.sample("after_create_sim_network", simTimer.elapsedTime(), nodesStuff)
        val logger = SimLogger(simTimer)
        logger.log("Starting hosts...")
//        startHosts(nodesStuff.map { it.host })
        logger.log("Starting programs...")
        val startFuture = startPrograms(nodesStuff)
        val programsStartedWallNanos = AtomicLong(-1)
        nodeHeapProfiler.sample("after_start_programs_scheduled", simTimer.elapsedTime(), nodesStuff)
        startFuture.handle { _, throwable ->
            if (throwable != null) {
                logger.log("Error on starting programs...")
                throwable.printStackTrace()
                throw throwable
            } else {
                programsStartedWallNanos.compareAndSet(-1, System.nanoTime())
                logger.log("All connected and all programs are started.")
            }
        }
        logger.log("Running simulated event loop...")

        val startSimT = simTimer.time()
        var nextAdvance: Duration = Duration.ZERO
        try {
            val isCompleteCheck = OncePerPeriod(1.milliseconds)
            val statusPrint = OncePerPeriod(1.seconds)
            val completedCount = AtomicInteger(0)
            val lastCompleteTime = AtomicReference(Duration.ZERO)
            nodesStuff.forEach { node ->
                node.completeTimeFuture.thenAccept {
                    completedCount.incrementAndGet()
                    lastCompleteTime.set(it)
                }
            }

            if (latencyWindowParallelism > 0) {
                val parallelBridge = simPacketPump as ParallelSimPacketBridge
                val simTimeCheckpoint = System.getProperty("quicsim.profile.simTimeCheckpointSeconds")
                    ?.toLongOrNull()
                    ?.seconds
                    ?: 30.seconds
                val checkpointWallNanos = AtomicLong(-1)
                val stopAtCheckpoint = AtomicBoolean(false)
                val stopAtCheckpointEnabled = System.getProperty("quicsim.profile.stopAtCheckpoint").toBoolean()
                parallelBridge.advanceWhile(
                    predicate = {
                        !stopAtCheckpoint.get() &&
                            (stopAtCheckpointEnabled || completedCount.get() < nodePrograms.size) &&
                            simTimer.elapsedTime() < maxSimulatedRunDuration
                    },
                    afterTimeAdvanced = { simTime ->
                        nodeHeapProfiler.maybeSample("parallel_periodic", simTime, nodesStuff)
                        if (simTime >= simTimeCheckpoint && checkpointWallNanos.compareAndSet(-1, System.nanoTime())) {
                            val startedAt = programsStartedWallNanos.get()
                            val wallSinceProgramsStartedMillis =
                                if (startedAt >= 0) {
                                    (checkpointWallNanos.get() - startedAt) / 1_000_000
                                } else {
                                    -1
                                }
                            logger.log(
                                "Sim time checkpoint reached: " +
                                    "checkpoint=${simTimeCheckpoint.inWholeSeconds}s " +
                                    "wallSinceProgramsStarted=${wallSinceProgramsStartedMillis}ms"
                            )
                            if (stopAtCheckpointEnabled) {
                                stopAtCheckpoint.set(true)
                            }
                        }
                    }
                )
                nodeHeapProfiler.sample("after_parallel_advance", simTimer.elapsedTime(), nodesStuff)
                if (stopAtCheckpoint.get()) {
                    logger.log(
                        "Stopped at sim time checkpoint; " +
                            "completed=${completedCount.get()}/${nodePrograms.size}"
                    )
                    return
                }

            } else {

                while (true) {
                    simPacketPump.advanceAndExecuteAll(nextAdvance)
                    val simTime = simTimer.time() - startSimT
                    nodeHeapProfiler.maybeSample("periodic", simTime, nodesStuff)

                    val maybeNextAdvance = simPacketPump.nextTaskDuration()

                    if (maybeNextAdvance == null || completedCount.get() == nodePrograms.size) {
                        break
                    }
                    nextAdvance = maybeNextAdvance

                    statusPrint.run(simTime) {
                        logger.log("Nodes complete $completedCount of $nodeCount")
                    }

                    if (simTime > maxSimulatedRunDuration) {
                        throw IllegalStateException(
                            "Simulation exceeded limit: simulated=${simTime.inWholeMilliseconds}ms " +
                                    "limit=${maxSimulatedRunDuration.inWholeMilliseconds}ms " +
                                    "completed=$completedCount/${nodePrograms.size}"
                        )
                    }
                }
            }

            logger.log("Last Node complete at $lastCompleteTime")
            nodeHeapProfiler.sample("completed", simTimer.elapsedTime(), nodesStuff)
        } catch (e: Exception) {
            logger.log("Exception: $e")
            nodeHeapProfiler.sample("exception", runCatching { simTimer.elapsedTime() }.getOrNull(), nodesStuff)
            throw e
        } finally {
            nodeHeapProfiler.close()
            simPacketPump.close()
        }

    }

    class OncePerPeriod(val period: Duration) {
        private var lastTime: Duration? = null
        fun run(curTime: Duration, body: () -> Unit) {
            if (shouldRun(curTime)) {
                body()
            }
        }

        fun shouldRun(curTime: Duration): Boolean {
            return if (lastTime == null || curTime > lastTime!! + period) {
                lastTime = curTime
                true
            } else false
        }
    }

    companion object {
        val defaultQuicAllocator: ByteBufAllocator = quicAllocatorFromSystemProperties()

        fun defaultQuicAllocatorFactoryFromSystemProperties(): (SimNodeId) -> ByteBufAllocator {
            val targetNodeId = System.getProperty("quicsim.nodeHeapProfile.nodeId")?.toIntOrNull()
                ?: return { defaultQuicAllocator }
            val dedicatedAllocator = System.getProperty("quicsim.nodeHeapProfile.dedicatedAllocator")
                ?.toBooleanStrictOrNull()
                ?: true
            if (!dedicatedAllocator) {
                return { defaultQuicAllocator }
            }

            val targetAllocator = quicAllocatorFromSystemProperties("quicsim.nodeHeapProfile.allocator", "unpooled")
            return { nodeId ->
                if (nodeId == targetNodeId) {
                    targetAllocator
                } else {
                    defaultQuicAllocator
                }
            }
        }

        private fun quicAllocatorFromSystemProperties(
            propertyName: String = "quicsim.profile.allocator",
            defaultMode: String = "adaptive"
        ): ByteBufAllocator =
            when (val allocatorMode = System.getProperty(propertyName, defaultMode)) {
                "adaptive" -> AdaptiveByteBufAllocator()
                "unpooled" -> UnpooledByteBufAllocator(true)
                else -> error("Unsupported $propertyName=$allocatorMode")
            }
    }
}
