package io.libp2p.quicsim.runner.graph.strategy

import io.libp2p.quicsim.runner.graph.TimeAdvanceStrategy
import io.libp2p.quicsim.runner.graph.TimedNetworkGraph
import io.libp2p.quicsim.runner.graph.TimedNetworkLink
import io.libp2p.quicsim.runner.graph.TimedNetworkVertex

class SimplestAdvanceStrategy<TVert: TimedNetworkVertex, TLink: TimedNetworkLink<TVert>> : TimeAdvanceStrategy<TVert, TLink> {

    override fun selectNextToAdvance(graph: TimedNetworkGraph<TVert, TLink>): TVert {
        return graph.vertices.maxByOrNull { graph.maxAdvance(it.id) }!!
    }
}