package io.libp2p.quicsim.runner

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.core.schedule.impl.SimpleMonotonicTimer
import io.libp2p.quicsim.runner.graph.TimeAdvanceStrategy
import io.libp2p.quicsim.runner.graph.TimedNetworkGraph
import io.libp2p.quicsim.runner.graph.strategy.SimplestAdvanceStrategy
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.BasicStarRouteResolver
import io.netty.channel.socket.DatagramPacket

class ParallelTimedNetworkController(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
    val routeResolver: RouteResolver = BasicStarRouteResolver(udpNet),
    val timeAdvanceStrategy: TimeAdvanceStrategy = SimplestAdvanceStrategy(),
    val parallelism: Int = Runtime.getRuntime().availableProcessors(),
) : NetworkController {

    val timedNetwork = TimedNetworkImpl(simNet, udpNet, routeResolver)
    val timedGraph: TimedNetworkGraph<*, *> = TimedNetworkGraph(timedNetwork.allNodes, timedNetwork.bidiLinks)
    override val monotonicTimer: SimpleMonotonicTimer = SimpleMonotonicTimer()

    val executor = PullingParallelTaskExecutor(parallelism)

    override fun advanceWhile(predicate: () -> Boolean) {
        executor.execute() {
            if (predicate()) getNextTask() else null
        }
    }

    private val vertexesInWork = mutableSetOf<String>()

    @Synchronized
    private fun getNextTask(): Runnable {
        val vertexToAdvance = getNextVertexToAdvance()
        vertexesInWork += vertexToAdvance.id
        val advance = timedGraph.maxAdvance(vertexToAdvance.id)

        return Runnable {
            timedGraph.advanceVertex(vertexToAdvance.id, advance)

            synchronized(this@ParallelTimedNetworkController) {
                vertexesInWork -= vertexToAdvance.id
                monotonicTimer.curT = max(monotonicTimer.curT, vertexToAdvance.time)
            }
        }
    }

    private fun getNextVertexToAdvance(): TimedNetworkImpl.GeneralNode {
        val priorityList = timeAdvanceStrategy.prioritize(timedGraph)
        return priorityList.first { it.id !in vertexesInWork } as TimedNetworkImpl.GeneralNode
    }
}
