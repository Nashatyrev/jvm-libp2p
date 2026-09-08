package io.libp2p.example.dc

/**
 * Gossipsub mesh degrees at the end of a run.
 *
 * The per-(node, topic) figure is what drives duplication: a node forwards a new message to every
 * peer in that topic's mesh, and each of those peers does the same before learning the node already
 * has it — so a node receives roughly one copy per mesh peer. Mesh sizes above
 * [GossipParams.D][io.libp2p.pubsub.gossip.GossipParams.D] therefore show up directly as
 * amplification above `D`.
 *
 * [sizes] and [perNodeDegrees] are kept sorted so `first()`/`last()` are the min/max.
 */
data class DcMeshStats(
    val nodeCount: Int,
    val sizes: List<Int>,
    val perNodeDegrees: List<Int>
) {
    val meshCount: Int get() = sizes.size

    /** Mean mesh peers per (node, topic) — the expected number of copies of each message. */
    val meanSize: Double get() = if (sizes.isEmpty()) 0.0 else sizes.average()

    override fun toString(): String = buildString {
        if (sizes.isEmpty()) {
            appendLine("mesh: no meshes recorded")
            return@buildString
        }
        appendLine(
            "mesh size per (node,topic): mean=%.2f min=%d p50=%d p95=%d max=%d (%d meshes over %d nodes)"
                .format(
                    meanSize,
                    sizes.first(),
                    sizes.intPercentile(0.50),
                    sizes.intPercentile(0.95),
                    sizes.last(),
                    meshCount,
                    nodeCount
                )
        )
        appendLine(
            "mesh degree per node (summed over topics): mean=%.1f min=%d p50=%d max=%d"
                .format(
                    perNodeDegrees.average(),
                    perNodeDegrees.first(),
                    perNodeDegrees.intPercentile(0.50),
                    perNodeDegrees.last()
                )
        )
        appendLine("mesh size histogram: " + sizes.groupingBy { it }.eachCount().toSortedMap())
    }

    companion object {
        /** [perNodeMeshSizes] has one list per node, holding that node's mesh size for each topic. */
        fun of(perNodeMeshSizes: List<List<Int>>): DcMeshStats =
            DcMeshStats(
                nodeCount = perNodeMeshSizes.size,
                sizes = perNodeMeshSizes.flatten().sorted(),
                perNodeDegrees = perNodeMeshSizes.map { it.sum() }.sorted()
            )
    }
}

/** Nearest-rank percentile over an already sorted list, matching [percentile] for durations. */
private fun List<Int>.intPercentile(fraction: Double): Int =
    this[kotlin.math.ceil(fraction * size).toInt().coerceIn(1, size) - 1]
