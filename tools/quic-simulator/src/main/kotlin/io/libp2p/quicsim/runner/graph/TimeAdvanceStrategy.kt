package io.libp2p.quicsim.runner.graph

interface TimeAdvanceStrategy {

    fun selectNextToAdvance(graph: TimedNetworkGraph<*, *>): TimedNetworkVertex =
        prioritize(graph).first()

    fun prioritize(graph: TimedNetworkGraph<*, *>): List<TimedNetworkVertex>

}