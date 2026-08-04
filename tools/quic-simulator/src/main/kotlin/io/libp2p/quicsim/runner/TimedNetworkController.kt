package io.libp2p.quicsim.runner

import io.libp2p.quicsim.runner.graph.TimeAdvanceStrategy
import io.libp2p.quicsim.runner.graph.TimedNetworkGraph
import io.libp2p.quicsim.sim.SimNet
import io.libp2p.quicsim.udpnetwork.RouteResolver
import io.libp2p.quicsim.udpnetwork.UdpSimNetwork
import io.netty.channel.socket.DatagramPacket
import kotlin.time.Duration.Companion.ZERO

class TimedNetworkController(
    val simNet: SimNet<DatagramPacket>,
    val udpNet: UdpSimNetwork,
    val routeResolver: RouteResolver,
    val timeAdvanceStrategy: TimeAdvanceStrategy
) {

    val timedNetwork = TimedNetworkImpl(simNet, udpNet, routeResolver)
    val timedGraph: TimedNetworkGraph<*, *> = TimedNetworkGraph(timedNetwork.allNodes, timedNetwork.bidiLinks)

    fun advanceWhile(predicate: () -> Boolean) {
        while (predicate()) {
            val node  = timeAdvanceStrategy.selectNextToAdvance(timedGraph) as TimedNetworkImpl.GeneralNode
            val advance = timedGraph.maxAdvance(node.id)
            timedGraph.advanceVertex(node.id, advance)
        }
    }
}
