package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.MappingPacketProcessor.Companion.map
import io.libp2p.quicsim.core.schedule.Controllable.Companion.advanceAndExecuteUntil
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl4
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class ParallelSimPacketBridge(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
    val idAndIp: Collection<IdMapEntry>,
    val parallelism: Int,
) : AbstractSimPacketBridge(idAndIp) {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    data class SimNodeWithUdpLinks(
        val simNode: SimNode<DatagramPacket>,
        val pump: ControllablePacketPump<DatagramPacket>
    )

    val latency = calcLatency()
    val allNodes = createAllNodes()
    private val parallelUdpNet = UdpSimNetworkEngineImpl4(udpNet)

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
    }

    private fun interface AdvanceAction {
        fun advance(advanceDuration: Duration);
    }

    fun advanceWhile(predicate: () -> Boolean) {
        val lock = Any()
        val executor = PrioritizedQuiescentExecutor(parallelism)

        class Task(
            val name: String,
            val priority: Int,
            var currentTime: Duration,
            val linkedTasks: MutableList<Task> = mutableListOf<Task>(),
            val advanceAction: AdvanceAction,
        ) {
            var pendingTime: Duration = currentTime
            var running: Boolean = false

            fun canAdvance() = synchronized(lock) {
                !running && linkedTasks.all { it.currentTime >= this.pendingTime }
            }

            fun advance() = synchronized(lock) {
//                println("-- [$name] Scheduled advance $pendingTime -> ${pendingTime + latency}")
                val advanceDuration = latency
                pendingTime += advanceDuration
                running = true
                executor.submit(priority) {
                    if (predicate()) {
//                        println("---- [$name] Advancing $currentTime -> ${currentTime + latency}")
                        val s = System.nanoTime()
                        val cntBefore = parallelUdpNet.totalPacketCount
                        advanceAction.advance(advanceDuration)
                        val t = (System.nanoTime() - s) / 1000 / 1000.0
                        val d = parallelUdpNet.totalPacketCount - cntBefore
//                        println("---- [$name] Advance complete $currentTime -> ${currentTime + latency} in $t ms, packets: $d")
                        onAdvanced(advanceDuration)
                    }
                }
            }

            fun onAdvanced(advanceDuration: Duration) = synchronized(lock) {
                currentTime += advanceDuration
                running = false
                advanceIfPossible()
                linkedTasks.forEach { it.advanceIfPossible() }
            }

            fun advanceIfPossible(): Unit = synchronized(lock) {
                if (predicate() && canAdvance()) {
                    advance()
                }
            }
        }

        val udpNetTask =
            Task(
                name = "udpNet",
                priority = 0,
                currentTime = parallelUdpNet.currentTime
            ) { advanceDuration ->
                parallelUdpNet.advanceUntil(advanceDuration)
                // just to increase the timer
                advance(advanceDuration)
            }

        val allNodesTasks = allNodes.map {
            Task(
                name = it.simNode.ip,
                priority = 1,
                currentTime = it.simNode.nodeTime.elapsedTime(),
                linkedTasks = mutableListOf(udpNetTask)
            ) { advanceDuration ->
                it.pump.advanceAndExecuteUntil(advanceDuration)
            }
        }

        udpNetTask.linkedTasks += allNodesTasks


        (listOf<Task>(udpNetTask) + allNodesTasks).forEach {
            it.advanceIfPossible()
        }

        executor.use { executor ->
            executor.awaitQuiescence()
        }
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        latency
}
