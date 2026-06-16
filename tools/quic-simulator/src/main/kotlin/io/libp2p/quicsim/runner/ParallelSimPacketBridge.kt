package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.MappingPacketProcessor.Companion.map
import io.libp2p.quicsim.core.schedule.Controllable
import io.libp2p.quicsim.core.schedule.Controllable.Companion.advanceAndExecuteUntil
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.UdpSimPacket
import io.libp2p.quicsim.udpnetwork.impl.ParallelUdpSimNetworkEngine
import io.netty.channel.socket.DatagramPacket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

class ParallelSimPacketBridge(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
    val idAndIp: Collection<IdMapEntry>,
) : AbstractSimPacketBridge(idAndIp) {

    data class SimNodeWithUdpLinks(
        val simNode: SimNode<DatagramPacket>,
        val pump: ControllablePacketPump<DatagramPacket>
    )

    val latency = calcLatency()
    val allNodes = createAllNodes()
    private val parallelUdpNet = ParallelUdpSimNetworkEngine(udpNet, drainEndpointBoundLatency = false)

    private fun calcLatency(): Duration {
        val latencies = udpNet.links.map { it.latencyQueue.minimalLatency }.distinct()
        require(latencies.size == 1) { "All nodes must have the same latency" }
        return latencies.first()
    }

    private fun createAllNodes(): List<SimNodeWithUdpLinks> {
        val simNodeByIp = simNet.allNodes.associateBy { it.ip }
        val udpNodeById = udpNet.nodes.associateBy { it.id }
        val nodesWithLinks = idAndIp.map { (id, ip) ->
            val simNode = simNodeByIp[ip]!!
            val udpNode = udpNodeById[id]!!
            createSimNodeWithUdpLinks(simNode, udpNode)
        }
        return nodesWithLinks
    }

    private fun createSimNodeWithUdpLinks(simNode: SimNode<DatagramPacket>, udpNode: UdpSimNode): SimNodeWithUdpLinks {
        val inboundUdpLink = udpNet.links.first { it.to == udpNode }
        val outboundUdpLink = udpNet.links.first { it.from == udpNode }

        val inboundEmitter = inboundUdpLink.latencyQueue.emitter
        val outboundReceiver = outboundUdpLink.latencyQueue.receiver

        val aheadProcessor = InOutProcessor(inboundEmitter, outboundReceiver)
        val aheadProcessorSim =
            aheadProcessor.map(nettyDatagramToSimUdpPacketConverter, simUdpPacketToNettyDatagramConverter)
        val controllable = ControllablePacketPump(simNode, aheadProcessorSim)
        return SimNodeWithUdpLinks(simNode, controllable)
    }

    override fun advanceImpl(advanceDuration: Duration) {
//        require(advanceDuration == latency || advanceDuration == Duration.ZERO)
//        allNodes.parallelStream().forEach {
//            it.pump.advanceAndExecuteUntil(advanceDuration)
//        }
//        parallelUdpNet.advanceAndExecuteUntil(advanceDuration)
    }

    fun advanceWhile(predicate: () -> Boolean) {
        val executor = Executors.newFixedThreadPool(1)

        val lock = Object()
        val inFlightTasks = AtomicInteger()
        val failure = AtomicReference<Throwable>()

        fun submit(task: () -> Unit) {
            inFlightTasks.incrementAndGet()
            executor.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                } finally {
                    synchronized(lock) {
                        if (inFlightTasks.decrementAndGet() == 0) {
                            lock.notifyAll()
                        }
                    }
                }
            }
        }

        fun awaitQuiescence() = synchronized(lock) {
            while (inFlightTasks.get() > 0) {
                lock.wait()
            }
        }

        class Task(
            val name: String,
            var currentTime: Duration,
            val linkedTasks: MutableList<Task> = mutableListOf<Task>(),
            val advanceAction: () -> Unit,
        ) {
            var pendingTime: Duration = currentTime

            fun canAdvance() = synchronized(lock) {
                linkedTasks.all { it.currentTime >= this.pendingTime }
            }
            fun advance() = synchronized(lock) {
                println("-- [$name] Scheduled advance $pendingTime -> ${pendingTime + latency}")
                pendingTime += latency
                submit {
                    if (predicate()) {
                        println("---- [$name] Advancing $currentTime -> ${currentTime + latency}")
                        advanceAction()
                        println("---- [$name] Advance complete $currentTime -> ${currentTime + latency}")
                        onAdvanced()
                    }
                }
            }

            fun onAdvanced()  = synchronized(lock) {
                currentTime += latency
                linkedTasks.forEach { it.advanceIfPossible() }
            }

            fun advanceIfPossible(): Unit = synchronized(lock) {
                if (predicate() && canAdvance()) {
                    advance()
                }
            }
        }

        val udpNetTask = Task("udpNet", parallelUdpNet.currentTime) {
            parallelUdpNet.advanceAndExecuteUntil(latency)
            // just to increase the timer
            advance(latency)
        }

        val allNodesTasks = allNodes.map {
            Task(it.simNode.ip,it.simNode.nodeTime.elapsedTime(), mutableListOf(udpNetTask)) {
                it.pump.advanceAndExecuteUntil(latency)
            }
        }

        udpNetTask.linkedTasks += allNodesTasks


        (listOf<Task>(udpNetTask) + allNodesTasks).forEach {
            it.advanceIfPossible()
        }

        awaitQuiescence()
        executor.shutdown()
        failure.get()?.let { throw it }
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        latency
}
