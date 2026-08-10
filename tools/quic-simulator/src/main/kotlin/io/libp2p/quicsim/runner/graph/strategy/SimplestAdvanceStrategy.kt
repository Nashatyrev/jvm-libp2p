package io.libp2p.quicsim.runner.graph.strategy

import io.libp2p.quicsim.runner.graph.TimeAdvanceStrategy
import io.libp2p.quicsim.runner.graph.TimedNetworkGraph
import io.libp2p.quicsim.runner.graph.TimedNetworkLink
import io.libp2p.quicsim.runner.graph.TimedNetworkVertex

class SimplestAdvanceStrategy : TimeAdvanceStrategy {

    override fun prioritize(graph: TimedNetworkGraph<*, *>): List<TimedNetworkVertex> {
        return graph.vertices.sortedByDescending { graph.maxAdvance(it.id) }
    }
}
