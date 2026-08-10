package io.libp2p.quicsim.runner.graph

interface TimeAdvanceStrategy {

    fun selectNextToAdvance(graph: TimedNetworkGraph<*, *>): TimedNetworkVertex =
        prioritize(graph).first()

    fun selectNextEligibleToAdvance(
        graph: TimedNetworkGraph<*, *>,
        isEligible: (TimedNetworkVertex) -> Boolean,
    ): TimedNetworkVertex? =
        prioritize(graph).firstOrNull(isEligible)

    fun prioritize(graph: TimedNetworkGraph<*, *>): List<TimedNetworkVertex>

}
