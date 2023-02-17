package io.libp2p.simulate

import java.util.Random

interface Topology {

    var random: Random
    fun connect(peers: List<SimPeer>): Network
}

class TopologyGraph(
    val edges: Collection<Edge>
) {

    constructor(vararg edges: Pair<Int, Int>) :
        this(
            edges.toList()
                .map { Edge(it.first, it.second) }
        )

    data class Edge(val srcV: Int, val destV: Int) {
        fun hasVertex(v: Int): Boolean = v == srcV || v == destV
        fun getOther(v: Int): Int =
            when (v) {
                srcV -> destV
                destV -> srcV
                else -> throw IllegalArgumentException("Edge $this has no vertex $v")
            }
    }

    val vertices = edges.flatMap { listOf(it.srcV, it.destV) }.toSet().sorted()

    fun getEdgesFrom(vertex: Int): List<Edge> = edges.filter { it.srcV == vertex }
    fun getEdgesTo(vertex: Int): List<Edge> = edges.filter { it.destV == vertex }
    fun getAllEdges(vertex: Int): List<Edge> = edges.filter { it.hasVertex(vertex) }

    fun calcMaxDistanceFrom(vertex: Int): Int {
        fun recurse(v: Int, remainingVertices: Set<Int>): Int {
            return getAllEdges(v)
                .filter { it.srcV in remainingVertices || it.destV in remainingVertices }
                .map {
                    val v1 = it.getOther(v)
                    recurse(v1, remainingVertices - v1) + 1
                }
                .maxOrNull() ?: 0
        }
        return recurse(vertex, vertices.toSet() - vertex)
    }
}
