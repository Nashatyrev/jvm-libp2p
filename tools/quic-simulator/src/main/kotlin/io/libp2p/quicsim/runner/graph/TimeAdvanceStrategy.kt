package io.libp2p.quicsim.runner.graph

interface TimeAdvanceStrategy {

    fun selectNextToAdvance(graph: TimedNetworkGraph<*, *>): TimedNetworkVertex

}