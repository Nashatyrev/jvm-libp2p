package io.libp2p.quicsim.runner

import com.google.common.collect.Comparators.max
import io.libp2p.quicsim.core.schedule.impl.SimpleMonotonicTimer
import io.libp2p.quicsim.runner.graph.TimeAdvanceStrategy
import io.libp2p.quicsim.runner.graph.TimedNetworkGraph
import io.libp2p.quicsim.runner.graph.strategy.SimplestAdvanceStrategy
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.libp2p.quicsim.udpnetwork.impl.ShortestPathRouteResolver
import io.netty.channel.socket.DatagramPacket

class TimedNetworkController(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
    val routeResolver: RouteResolver = ShortestPathRouteResolver(udpNet),
    val timeAdvanceStrategy: TimeAdvanceStrategy = SimplestAdvanceStrategy()
) : NetworkController {

    val timedNetwork = TimedNetworkImpl(simNet, udpNet, routeResolver)
    val timedGraph: TimedNetworkGraph<*, *> = TimedNetworkGraph(timedNetwork.allNodes, timedNetwork.bidiLinks)
    override val monotonicTimer: SimpleMonotonicTimer = SimpleMonotonicTimer()

    override fun advanceWhile(predicate: () -> Boolean) {
        while (predicate()) {
            val node  = timeAdvanceStrategy.selectNextToAdvance(timedGraph) as TimedNetworkImpl.GeneralNode
            val advance = timedGraph.maxAdvance(node.id)
            timedGraph.advanceVertexTime(node.id, advance)
            monotonicTimer.curT = max(monotonicTimer.curT, node.time)
        }
    }
}
