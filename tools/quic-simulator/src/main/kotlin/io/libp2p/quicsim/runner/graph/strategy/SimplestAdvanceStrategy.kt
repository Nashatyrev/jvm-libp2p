package io.libp2p.quicsim.runner.graph.strategy

import io.libp2p.quicsim.runner.graph.TimeAdvanceStrategy
import io.libp2p.quicsim.runner.graph.TimedNetworkGraph
import io.libp2p.quicsim.runner.graph.TimedNetworkLink
import io.libp2p.quicsim.runner.graph.TimedNetworkVertex
import kotlin.time.Duration

class SimplestAdvanceStrategy : TimeAdvanceStrategy {

    override fun selectNextEligibleToAdvance(
        graph: TimedNetworkGraph<*, *>,
        isEligible: (TimedNetworkVertex) -> Boolean,
    ): TimedNetworkVertex? {
        var selected: TimedNetworkVertex? = null
        var selectedAdvance: Duration? = null
        graph.vertices.forEach { vertex ->
            if (!isEligible(vertex)) {
                return@forEach
            }
            val advance = graph.maxAdvance(vertex.id)
            if (selectedAdvance == null || advance > selectedAdvance!!) {
                selected = vertex
                selectedAdvance = advance
            }
        }
        return selected
    }

    override fun prioritize(graph: TimedNetworkGraph<*, *>): List<TimedNetworkVertex> {
        return graph.vertices.sortedByDescending { graph.maxAdvance(it.id) }
    }
}
