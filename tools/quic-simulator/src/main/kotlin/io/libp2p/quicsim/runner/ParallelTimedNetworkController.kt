package io.libp2p.quicsim.runner

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.core.schedule.impl.SimpleMonotonicTimer
import io.libp2p.quicsim.runner.graph.IncrementalTimeAdvanceScheduler
import io.libp2p.quicsim.runner.graph.TimedNetworkGraph
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.BasicStarRouteResolver
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration

class ParallelTimedNetworkController(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
    val routeResolver: RouteResolver = BasicStarRouteResolver(udpNet),
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
) : NetworkController {

    val timedNetwork = TimedNetworkImpl(simNet, udpNet, routeResolver)
    val timedGraph: TimedNetworkGraph<TimedNetworkImpl.GeneralNode, TimedNetworkImpl.GeneralBidiLink> =
        TimedNetworkGraph(timedNetwork.allNodes, timedNetwork.bidiLinks)
    override val monotonicTimer: SimpleMonotonicTimer = SimpleMonotonicTimer()

    val executor = PullingParallelTaskExecutor(parallelism)
    private val timeAdvanceScheduler = IncrementalTimeAdvanceScheduler(timedGraph) {
        it is TimedNetworkImpl.RouterNode
    }

    override fun advanceWhile(predicate: () -> Boolean) {
        executor.execute() {
            if (predicate()) getNextTask(predicate) else null
        }
    }

    private val deferredControllables = timedNetwork.allNodes.associateWith { node ->
        DeferredControllable(node.controllable, node.time)
    }

    @Synchronized
    private fun getNextTask(predicate: () -> Boolean): Runnable? {
        while (predicate()) {
            val vertexAdvance = timeAdvanceScheduler.reserveNext() ?: return null
            val vertexToAdvance = vertexAdvance.vertex
            val advance = vertexAdvance.duration

            val targetTime = vertexToAdvance.time + advance
            val deferredControllable = deferredControllables.getValue(vertexToAdvance)
            if (!deferredControllable.hasTaskThrough(targetTime)) {
                advanceVertexTime(vertexToAdvance, advance)
                timeAdvanceScheduler.complete(vertexAdvance)
                continue
            }

            return Runnable {
                var completed = false
                try {
                    deferredControllable.executeThrough(targetTime)
                    synchronized(this@ParallelTimedNetworkController) {
                        advanceVertexTime(vertexToAdvance, advance)
                        timeAdvanceScheduler.complete(vertexAdvance)
                        completed = true
                    }
                } finally {
                    if (!completed) {
                        synchronized(this@ParallelTimedNetworkController) {
                            timeAdvanceScheduler.release(vertexAdvance)
                        }
                    }
                }
            }
        }

        return null
    }

    private fun advanceVertexTime(
        vertex: TimedNetworkImpl.GeneralNode,
        advance: Duration,
    ) {
        timedGraph.advanceVertexTime(vertex.id, advance)
        monotonicTimer.curT = max(monotonicTimer.curT, vertex.time)
    }

}
