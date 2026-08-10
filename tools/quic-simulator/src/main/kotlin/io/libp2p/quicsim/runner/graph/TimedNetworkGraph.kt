package io.libp2p.quicsim.runner.graph

import io.libp2p.quicsim.runner.graph.TimedNetworkLink.Companion.connects
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Undirected acyclic network graph for experimenting with per-vertex time advancement.
 */
class TimedNetworkGraph<TVert: TimedNetworkVertex, TLink: TimedNetworkLink<TVert>>(
    val vertices: List<TVert>,
    val links: List<TLink>
) {
    val verticesById: Map<String, TVert> = this.vertices.associateBy { it.id }

    inner class TimedNetworkNeighbour(
        val vertex: TVert,
        val link: TLink
    )

    private val adjacency: Map<String, List<TimedNetworkNeighbour>>

    init {
        TimedNetworkGraphValidator(vertices, links, verticesById).validate()

        val adjacencyBuilder = verticesById.keys.associateWith { mutableListOf<TimedNetworkNeighbour>() }
        this.links.forEach { link ->
            adjacencyBuilder.getValue(link.left.id) += TimedNetworkNeighbour(link.right, link)
            adjacencyBuilder.getValue(link.right.id) += TimedNetworkNeighbour(link.left, link)
        }
        adjacency = adjacencyBuilder.mapValues { it.value.toList() }
    }

    fun vertex(vertexId: String): TVert =
        verticesById[vertexId] ?: error("Unknown network vertex: $vertexId")

    fun neighbours(vertexId: String): List<TimedNetworkNeighbour> {
        vertex(vertexId)
        return adjacency.getValue(vertexId)
    }

    fun linkBetween(left: String, right: String): TLink? {
        val leftVertex = vertex(left)
        val rightVertex = vertex(right)
        return links.firstOrNull { it.connects(leftVertex) && it.connects(rightVertex) }
    }

    fun advanceVertexTime(vertexId: String, advanceDuration: Duration) {
        require(!advanceDuration.isNegative()) { "advanceDuration must not be negative" }
        val vertex = vertex(vertexId)
        vertex.advanceTime(advanceDuration)
    }

    fun canAdvanceVertex(vertexId: String, advanceDuration: Duration): Boolean {
        require(!advanceDuration.isNegative()) { "advanceDuration must not be negative" }
        val vertex = vertex(vertexId)
        val advancedTime = vertex.time + advanceDuration
        return neighbours(vertexId).all { neighbour ->
            (advancedTime - neighbour.vertex.time).absoluteValue <= neighbour.link.latency
        }
    }

    fun maxAdvance(vertexId: String): Duration {
        val vertex = vertex(vertexId)
        return neighbours(vertexId).minOf { neighbour ->
            val maxVertexTime = neighbour.vertex.time + neighbour.link.latency
            if (maxVertexTime <= vertex.time) ZERO else maxVertexTime - vertex.time
        }
    }
}
