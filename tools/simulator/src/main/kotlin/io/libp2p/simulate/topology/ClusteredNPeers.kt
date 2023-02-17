package io.libp2p.simulate.topology

import io.libp2p.tools.get
import org.jgrapht.Graph
import org.jgrapht.Graphs
import org.jgrapht.generate.RandomRegularGraphGenerator
import org.jgrapht.graph.DefaultUndirectedGraph
import java.util.Random

class WVertex(val weight: Double)
class WEdge(val cnt: Int)

class ClusteredNPeers(val peersCount: Int, val clusters: Graph<WVertex, WEdge>) : AbstractGraphTopology() {
    override var random = Random()

    override fun <T> buildGraph(peers: List<T>): Graph<T, Any> {
        val clusterVertexes = clusters.vertexSet().toList()
        val clusterGraphs =
            split(peers, clusterVertexes.map { it.weight }).map {
                val graph = DefaultUndirectedGraph(it.iterator()::next, { Any() }, false)
                RandomRegularGraphGenerator<T, Any>(it.size, peersCount, random).generateGraph(graph)
                graph
            }
        val targetGraph = clusterGraphs.fold(DefaultUndirectedGraph<T, Any>(Any::class.java)) { tg, g ->
            Graphs.addGraph(tg, g)
            tg
        }

        clusters.edgeSet().forEach { edge ->
            val freeUpNodes: (Graph<T, Any>) -> List<T> = { cluster ->
                cluster.edgeSet()
                    .shuffled(random)
                    .take((edge.cnt + 1) / 2)
                    .flatMap {
                        val ret = listOf(cluster.getEdgeSource(it), cluster.getEdgeTarget(it))
                        val rc = targetGraph.removeEdge(it)
                        ret
                    }
            }
            val cluster1 = clusterGraphs[clusterVertexes.indexOf(clusters.getEdgeSource(edge))]
            val cluster2 = clusterGraphs[clusterVertexes.indexOf(clusters.getEdgeTarget(edge))]
            freeUpNodes(cluster1)
                .zip(freeUpNodes(cluster2))
                .forEach {
                    targetGraph.addEdge(it.first, it.second, Any())
                }
        }

        return targetGraph
    }

    fun <T> split(col: List<T>, weights: Collection<Double>): List<List<T>> {
        val sum = weights.sum()
        val indices = listOf(0) + weights
            .fold(listOf<Double>()) { l, d -> l + (d + (l.lastOrNull() ?: 0.0)) }
            .map { (col.size * (it / sum)).toInt() }
        return indices.zipWithNext().map { col[it.first until it.second] }
    }
}