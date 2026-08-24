package io.libp2p.quicsim.runner.graph

import java.util.PriorityQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Selects advanceable vertices without recalculating the whole graph after each advance.
 */
class IncrementalTimeAdvanceScheduler<TVert : TimedNetworkVertex, TLink : TimedNetworkLink<TVert>>(
    private val graph: TimedNetworkGraph<TVert, TLink>,
    private val isPrioritized: (TVert) -> Boolean,
) {

    data class VertexAdvance<TVert : TimedNetworkVertex>(
        val vertex: TVert,
        val duration: Duration,
    )

    private data class QueueEntry<TVert : TimedNetworkVertex>(
        val vertex: TVert,
        val duration: Duration,
        val version: Long,
    )

    private val entryVersions = mutableMapOf<String, Long>()
    private val knownAdvances = mutableMapOf<String, Duration>()
    private val reservedVertexIds = mutableSetOf<String>()
    private val reservationFootprintsByVertexId = mutableMapOf<String, Set<String>>()
    private val entries = PriorityQueue(
        compareByDescending<QueueEntry<TVert>> { isPrioritized(it.vertex) }
            .thenByDescending { it.duration }
            .thenBy { it.vertex.id }
    )

    init {
        graph.vertices.forEach(::refresh)
    }

    fun reserveNext(): VertexAdvance<TVert>? {
        val blockedEntries = mutableListOf<QueueEntry<TVert>>()
        try {
            while (entries.isNotEmpty()) {
                val entry = entries.remove()
                if (entryVersions[entry.vertex.id] != entry.version) {
                    continue
                }

                val footprint = reservationFootprint(entry.vertex)
                if (footprint.any { it in reservedVertexIds }) {
                    blockedEntries += entry
                    continue
                }

                reservedVertexIds += footprint
                reservationFootprintsByVertexId[entry.vertex.id] = footprint
                return VertexAdvance(entry.vertex, graph.maxAdvance(entry.vertex.id))
            }

            return null
        } finally {
            entries += blockedEntries
        }
    }

    /** Call after the selected vertex has advanced in [graph]. */
    fun complete(advance: VertexAdvance<TVert>) {
        val footprint = reservationFootprintsByVertexId.remove(advance.vertex.id)
        check(footprint != null) {
            "Vertex ${advance.vertex.id} was not reserved"
        }
        reservedVertexIds -= footprint
        refreshAffectedVertices(advance.vertex)
    }

    /** Releases a selected vertex when its work could not be completed. */
    fun release(advance: VertexAdvance<TVert>) {
        val footprint = reservationFootprintsByVertexId.remove(advance.vertex.id)
        check(footprint != null) {
            "Vertex ${advance.vertex.id} was not reserved"
        }
        reservedVertexIds -= footprint
        refresh(advance.vertex)
    }

    private fun reservationFootprint(vertex: TVert): Set<String> =
        buildSet {
            add(vertex.id)
            graph.neighbours(vertex.id).forEach { neighbour -> add(neighbour.vertex.id) }
        }

    private fun refreshAffectedVertices(vertex: TVert) {
        refresh(vertex)
        graph.neighbours(vertex.id)
            .map { it.vertex }
            // A neighbour advancing can only increase this vertex's available advance.
            .filter { neighbour ->
                neighbour.id !in reservedVertexIds &&
                    (isPrioritized(neighbour) || knownAdvances.getValue(neighbour.id) == ZERO)
            }
            .forEach(::refresh)
    }

    private fun refresh(vertex: TVert) {
        val version = (entryVersions[vertex.id] ?: 0L) + 1L
        entryVersions[vertex.id] = version
        val duration = graph.maxAdvance(vertex.id)
        knownAdvances[vertex.id] = duration
        if (duration > ZERO) {
            entries += QueueEntry(vertex, duration, version)
        }
    }
}
