package io.libp2p.quicsim.runner.graph

interface TimeAdvanceStrategy<TVert: TimedNetworkVertex, TLink: TimedNetworkLink<TVert>> {

    fun selectNextToAdvance(graph: TimedNetworkGraph<TVert, TLink>): TVert

}