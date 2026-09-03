package io.libp2p.example.dc

import io.libp2p.quicsim.udpnetwork.Bandwidth

/**
 * Description of one group of nodes, configured as a block rather than as a positional argument
 * list:
 *
 * ```
 * builder.addGroup(count = 60) {
 *     spreadOverRegions()
 *     bandwidth = Bandwidths.RESIDENTIAL
 *     validators = 1
 *     peers = 20
 *     subnetsByIndex { index -> setOf(index % 64) }
 * }
 * ```
 *
 * Placement, validator allocation and subnet assignment each offer alternatives that are mutually
 * exclusive; setting two of the same kind fails fast in [validated].
 */
class DcNodeGroup<R> internal constructor(
    private val allRegions: List<R>
) {
    // --- placement: at most one of the three ------------------------------------------------

    /** Put the whole group in this single region. */
    var region: R? = null

    /** Spread the group round-robin over these regions. */
    var regions: List<R>? = null

    /** Split the group over regions by weight, using the largest remainder method. */
    var regionWeights: Map<R, Double>? = null

    // --- node attributes ---------------------------------------------------------------------

    /** Access link rate of every node in the group. Required. */
    var bandwidth: Bandwidth? = null

    /** Validators run by each node of the group. Mutually exclusive with [validatorsTotal]. */
    var validators: Int = 0

    /** Validators shared out evenly across the group. Mutually exclusive with [validators]. */
    var validatorsTotal: Int? = null

    /** Gossip peers each node of the group connects to. */
    var peers: Int = DEFAULT_PEER_COUNT

    /** Attestation subnets every node of the group subscribes to. */
    var subnets: Set<Int> = emptySet()

    private var subnetIdsOf: ((Int) -> Set<Int>)? = null

    /** Spread the group round-robin over [regions], or over every known region if none are given. */
    fun spreadOverRegions(vararg regions: R) {
        this.regions = if (regions.isEmpty()) allRegions else regions.toList()
    }

    /**
     * Assigns subnets per node, called with the index of the node *within this group*. Mutually
     * exclusive with [subnets].
     */
    fun subnetsByIndex(subnetIdsOf: (Int) -> Set<Int>) {
        this.subnetIdsOf = subnetIdsOf
    }

    internal fun validated(): DcNodeGroup<R> = apply {
        val placements = listOfNotNull(
            region?.let { "region" },
            regions?.let { "regions" },
            regionWeights?.let { "regionWeights" }
        )
        require(placements.size <= 1) { "Set at most one of region/regions/regionWeights, got $placements" }
        require(bandwidth != null) { "bandwidth is required" }
        require(validators == 0 || validatorsTotal == null) {
            "Set either validators or validatorsTotal, not both"
        }
        require(validators >= 0) { "validators must be >= 0, got $validators" }
        validatorsTotal?.let { require(it >= 0) { "validatorsTotal must be >= 0, got $it" } }
        require(peers >= 0) { "peers must be >= 0, got $peers" }
        require(subnets.isEmpty() || subnetIdsOf == null) {
            "Set either subnets or subnetsByIndex, not both"
        }
        region?.let { require(it in allRegions) { "Unknown region: $it" } }
        regions?.let { list ->
            require(list.isNotEmpty()) { "regions must not be empty" }
            list.forEach { require(it in allRegions) { "Unknown region: $it" } }
        }
    }

    /** Region of each node of the group, in order. */
    internal fun regionsFor(count: Int): List<R> {
        region?.let { single -> return List(count) { single } }
        regionWeights?.let { weights -> return weightedRegions(count, weights) }
        val roundRobin = regions ?: allRegions
        return List(count) { index -> roundRobin[index % roundRobin.size] }
    }

    /** Validator count of each node of the group, in order. */
    internal fun validatorsFor(count: Int): List<Int> =
        validatorsTotal?.let { total -> distributeEvenly(total, count) } ?: List(count) { validators }

    /** Subnets of the node at [index] within the group. */
    internal fun subnetsFor(index: Int): Set<Int> {
        val ids = subnetIdsOf?.invoke(index) ?: subnets
        require(ids.all { it >= 0 }) { "attestation subnet ids must be >= 0, got $ids" }
        return ids.toSet()
    }

    private fun weightedRegions(count: Int, weights: Map<R, Double>): List<R> {
        require(weights.isNotEmpty()) { "regionWeights must not be empty" }
        weights.keys.forEach { require(it in allRegions) { "Unknown region: $it" } }
        require(weights.values.all { it >= 0.0 }) { "Region weights must be >= 0: $weights" }
        val weightSum = weights.values.sum()
        require(weightSum > 0.0) { "Region weights must not sum to zero: $weights" }

        val entries = weights.entries.toList()
        val exact = entries.map { (_, weight) -> count * weight / weightSum }
        val counts = exact.map { it.toInt() }.toMutableList()
        var remaining = count - counts.sum()
        exact.mapIndexed { index, value -> index to (value - counts[index]) }
            .sortedByDescending { it.second }
            .forEach { (index, _) ->
                if (remaining > 0) {
                    counts[index]++
                    remaining--
                }
            }
        return entries.flatMapIndexed { index, entry -> List(counts[index]) { entry.key } }
    }

    companion object {
        /** Peers a node connects to when the group does not say otherwise. */
        const val DEFAULT_PEER_COUNT: Int = 20
    }
}

/** Splits [total] into [parts] near-equal integers, larger parts first. Sums to [total]. */
internal fun distributeEvenly(total: Int, parts: Int): List<Int> {
    require(parts > 0) { "parts must be > 0, got $parts" }
    val base = total / parts
    val remainder = total % parts
    return List(parts) { index -> base + if (index < remainder) 1 else 0 }
}
