package io.libp2p.quicsim.runner

import io.libp2p.quicsim.core.ControllablePacketPump
import io.libp2p.quicsim.core.InOutProcessor
import io.libp2p.quicsim.core.schedule.Controllable.Companion.advanceAndExecuteUntil
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.sim.SimNode
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.UdpSimNode
import io.libp2p.quicsim.udpnetwork.impl.UdpSimNetworkEngineImpl4
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class ParallelSimPacketBridge(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
    val parallelism: Int,
) : AbstractSimPacketBridge() {
    init {
        require(parallelism > 0) { "parallelism must be positive" }
    }

    data class SimNodeWithUdpLinks(
        val udpNodeId: String,
        val simNode: SimNode<DatagramPacket>,
        val pump: ControllablePacketPump<DatagramPacket>
    )

    val latency = calcLatency()
    private val advanceStep =
        System.getProperty("quicsim.parallel.advanceStepMillis")
            ?.toLongOrNull()
            ?.milliseconds
            ?: latency
    val allNodes = createAllNodes()
    private val parallelUdpNet = UdpSimNetworkEngineImpl4(udpNet)

    init {
        require(advanceStep > Duration.Companion.ZERO) { "advance step must be positive" }
    }

    private fun calcLatency(): Duration {
        val latencies = udpNet.links.map { it.latencyQueue.minimalLatency }.distinct()
        require(latencies.size == 1) { "All nodes must have the same latency" }
        return latencies.first()
    }

    private fun createAllNodes(): List<SimNodeWithUdpLinks> {
        val simNodeByIp = simNet.allNodes.associateBy { it.ip }
        val nodesWithLinks = udpNet.nodes.map { udpNode ->
            val simNode = simNodeByIp[udpNode.id]!!
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
        val controllable = ControllablePacketPump(simNode, aheadProcessor)
        return SimNodeWithUdpLinks(udpNode.id, simNode, controllable)
    }

    override fun advanceImpl(advanceDuration: Duration) {
    }

    private fun interface AdvanceAction {
        fun advance(advanceDuration: Duration);
    }

    private fun interface NextTaskDurationAction {
        fun nextTaskDuration(): Duration?
    }

    fun advanceWhile(
        predicate: () -> Boolean,
        afterTimeAdvanced: (Duration) -> Unit = {}
    ) {
        val lock = Any()
        val executor = PrioritizedQuiescentExecutor(parallelism)

        class Task(
            val name: String,
            val priority: Int,
            var currentTime: Duration,
            val linkedTasks: MutableList<Task> = mutableListOf<Task>(),
            val advanceAction: AdvanceAction,
            val nextTaskDurationAction: NextTaskDurationAction? = null,
            val afterAdvancedAction: () -> Unit = {},
        ) {
            var pendingTime: Duration = currentTime
            var running: Boolean = false
            var skippedAdvance: Duration = Duration.Companion.ZERO
            private var cachedNextTaskDuration: Duration? = null
            private var cachedNextTaskDurationValid: Boolean = false

            fun canAdvance() = synchronized(lock) {
                !running && linkedTasks.all { it.currentTime >= this.pendingTime }
            }

            fun invalidateNextTaskDuration() {
                cachedNextTaskDuration = null
                cachedNextTaskDurationValid = false
            }

            private fun nextTaskDuration(): Duration? {
                if (!cachedNextTaskDurationValid) {
                    cachedNextTaskDuration = nextTaskDurationAction?.nextTaskDuration()
                    cachedNextTaskDurationValid = true
                }
                return cachedNextTaskDuration
            }

            private fun shouldExecute(advanceDuration: Duration): Boolean {
                if (nextTaskDurationAction == null) {
                    return true
                }
                val totalAdvance = skippedAdvance + advanceDuration
                return nextTaskDuration()?.let { nextTaskDuration ->
                    nextTaskDuration <= totalAdvance
                } ?: false
            }

            fun advance() = synchronized(lock) {
                val advanceDuration = advanceStep
                pendingTime += advanceDuration
                if (!shouldExecute(advanceDuration)) {
                    skippedAdvance += advanceDuration
                    onAdvanced(advanceDuration, executed = false)
                    return@synchronized
                }
                val executeAdvance = skippedAdvance + advanceDuration
                skippedAdvance = Duration.Companion.ZERO
                invalidateNextTaskDuration()
                running = true
                executor.submit(priority) {
                    if (predicate()) {
                        advanceAction.advance(executeAdvance)
                        onAdvanced(advanceDuration, executed = true)
                    }
                }
            }

            fun onAdvanced(advanceDuration: Duration, executed: Boolean) = synchronized(lock) {
                if (executed) {
                    invalidateNextTaskDuration()
                }
                currentTime += advanceDuration
                running = false
                afterAdvancedAction()
                advanceIfPossible()
                linkedTasks.forEach { it.advanceIfPossible() }
            }

            fun advanceIfPossible(): Unit = synchronized(lock) {
                if (predicate() && canAdvance()) {
                    advance()
                }
            }
        }

        lateinit var udpNetTask: Task
        val nodeTasksByUdpId = mutableMapOf<String, Task>()

        udpNetTask =
            Task(
                name = "udpNet",
                priority = 0,
                currentTime = parallelUdpNet.currentTime,
                advanceAction = AdvanceAction { advanceDuration ->
                    parallelUdpNet.advanceUntil(advanceDuration)
                    // just to increase the timer
                    advance(advanceDuration)
                },
                afterAdvancedAction = {
                    parallelUdpNet.lastDeliveredEndpointNodeIds.forEach { nodeId ->
                        nodeTasksByUdpId[nodeId]?.invalidateNextTaskDuration()
                    }
                    afterTimeAdvanced(udpNetTask.currentTime)
                },
            )

        val allNodesTasks = allNodes.map {
            val task = Task(
                name = it.simNode.ip,
                priority = 1,
                currentTime = it.simNode.nodeTime.elapsedTime(),
                linkedTasks = mutableListOf(udpNetTask),
                advanceAction = { advanceDuration ->
                    it.pump.advanceAndExecuteUntil(advanceDuration)
                },
                nextTaskDurationAction = {
                    it.pump.nextTaskDuration()
                },
            )
            nodeTasksByUdpId[it.udpNodeId] = task
            task
        }

        udpNetTask.linkedTasks += allNodesTasks


        (listOf<Task>(udpNetTask) + allNodesTasks).forEach {
            it.advanceIfPossible()
        }

        executor.use {
            it.awaitQuiescence()
        }
    }

    override fun executePending() {
    }

    override fun nextTaskDuration(): Duration? =
        latency
}
