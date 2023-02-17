package io.libp2p.simulate.topology

import org.jgrapht.Graph
import org.jgrapht.generate.RandomRegularGraphGenerator
import org.jgrapht.graph.DefaultUndirectedGraph
import java.util.Random

data class RandomNPeers(val peersCount: Int = 10) : AbstractGraphTopology() {
    override var random = Random()

    override fun <T> buildGraph(peers: List<T>): Graph<T, Any> {
        val peersVertex = peers.iterator()
        val graph: Graph<T, Any> = DefaultUndirectedGraph(peersVertex::next, { Any() }, false)
        RandomRegularGraphGenerator<T, Any>(peers.size, peersCount, random).generateGraph(graph)
        return graph
    }
}